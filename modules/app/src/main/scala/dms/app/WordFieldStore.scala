package dms.app

import dms.app.OfficePackage.{Entries, children, is}
import org.w3c.dom.{Document, Element}
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** The field values of a filled Word document kept next to its text (DMS-127, ADR 0049), so that the keys
  * stay recognizable in the background while the generated text is visible:
  *
  *   - a **Custom XML part** (`customXml/itemN.xml`, namespace [[Namespace]]) with one element per key, e.g.
  *     `<documentNumber>VT-2026-0007</documentNumber>`; plain text content controls are bound to it
  *     (`w:dataBinding`), so Word keeps control and element in sync when the text is edited in Word;
  *   - **custom document properties** (`docProps/custom.xml`), one per key named `dms.<key>` (Word: File →
  *     Info → Properties → Advanced → Custom; usable in `DOCPROPERTY` fields).
  *
  * Both carry the raw value: text as printed, dates as ISO date (`yyyy-MM-dd`), check boxes `true`/`false`,
  * rows as JSON array of objects by column key. Both are rewritten completely on every fill (keys without
  * value are removed); other custom properties and other custom XML parts stay as they are. Deterministic:
  * fixed part id, keys sorted.
  */
private[app] object WordFieldStore:

  /** Namespace of the custom XML part with the values. */
  val Namespace: String = "urn:dms:fields:1"

  /** Prefix of the custom document properties with the values. */
  val PropertyPrefix: String = "dms."

  /** The fixed id of the custom XML part (one per document; a fixed id keeps filling deterministic). */
  val StoreItemId: String =
    "{" + UUID.nameUUIDFromBytes(Namespace.getBytes(UTF_8)).toString.toUpperCase(java.util.Locale.ROOT) + "}"

  /** The XPath of the element of `key` in the part, for `w:dataBinding`. */
  def xpath(key: String): String = s"/dms:fields[1]/dms:$key[1]"

  val PrefixMappings: String = s"xmlns:dms='$Namespace'"

  private val Relationships = "http://schemas.openxmlformats.org/package/2006/relationships"
  private val ContentTypes  = "http://schemas.openxmlformats.org/package/2006/content-types"
  private val CustomXmlRel  = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml"
  private val PropsRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXmlProps"
  private val CustomPropertiesRel =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties"
  private val CustomProperties = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
  private val VariantTypes     = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
  private val DataStore        = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"

  /** Format id of user-defined properties. */
  private val UserDefined = "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}"

  private val ItemPart       = "customXml/item(\\d+)\\.xml".r
  private val PropertiesPart = "docProps/custom.xml"
  private val Kind           = "Word document (DOCX)"

  /** The raw value stored for a filled value. */
  def raw(value: FieldValue): String = value match
    case FieldValue.Text(t)    => t
    case FieldValue.Date(d, _) => d.toString
    case FieldValue.Check(c)   =>
      if c then dms.templates.WordFields.Checked else dms.templates.WordFields.Unchecked
    case FieldValue.Rows(rows) =>
      Json
        .Arr(rows.map(r => Json.Obj(r.toList.sortBy(_._1).map((k, v) => k -> Json.Str(raw(v)))*))*)
        .toJson

  /** The values stored in a package (raw, by key). */
  final case class Stored(xml: Map[String, String], properties: Map[String, String], filled: Boolean)

  /** The values stored in a package: those of the custom XML part and those of the custom properties. */
  def read(entries: Entries): Either[String, Stored] =
    for
      items <- OfficePackage.parse(entries, ItemPart.matches, Kind)
      props <- OfficePackage.parse(entries, _ == PropertiesPart, Kind)
    yield
      val part       = items.map(_._2.getDocumentElement).find(isFields)
      val xml        = part.toList.flatMap(children).map(e => e.getLocalName -> e.getTextContent).toMap
      val properties = props.headOption.toList
        .flatMap((_, doc) => children(doc.getDocumentElement))
        .filter(is(_, CustomProperties, "property"))
        .flatMap { p =>
          Option(p.getAttribute("name"))
            .filter(_.startsWith(PropertyPrefix))
            .map(n => n.substring(PropertyPrefix.length) -> children(p).headOption.fold("")(_.getTextContent))
        }
        .toMap
      Stored(xml, properties, part.isDefined || properties.nonEmpty)

  /** The entries to replace or add so that the package carries `values` (by key) in its custom XML part and
    * custom properties. Nothing is added to a package without such parts when there are no values.
    */
  def write(entries: Entries, values: Map[String, String]): Either[String, Entries] =
    OfficePackage.attempt {
      val byName = entries.toMap
      val sorted = values.toList.sortBy(_._1)
      val parsed =
        OfficePackage.parse(entries, n => ItemPart.matches(n), Kind).fold(m => throw Exception(m), identity)
      val ours    = parsed.find((_, doc) => isFields(doc.getDocumentElement)).map(_._1)
      val hasProp = byName.contains(PropertiesPart)
      if values.isEmpty && ours.isEmpty && !hasProp then Nil
      else
        val number = ours.flatMap(n => ItemPart.findFirstMatchIn(n).map(_.group(1).toInt)).getOrElse {
          entries
            .flatMap((n, _) => ItemPart.findFirstMatchIn(n).map(_.group(1).toInt))
            .maxOption
            .getOrElse(0) + 1
        }
        val item      = s"customXml/item$number.xml"
        val itemProps = s"customXml/itemProps$number.xml"
        val itemRels  = s"customXml/_rels/item$number.xml.rels"
        val xmlPart   =
          s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><fields xmlns="$Namespace">""" +
            sorted.map((k, v) => s"<$k>${escape(v)}</$k>").mkString + "</fields>"
        val propsPart =
          s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            s"""<ds:datastoreItem ds:itemID="$StoreItemId" xmlns:ds="$DataStore"><ds:schemaRefs>""" +
            s"""<ds:schemaRef ds:uri="$Namespace"/></ds:schemaRefs></ds:datastoreItem>"""
        val relsPart =
          s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$Relationships">""" +
            s"""<Relationship Id="rId1" Type="$PropsRel" Target="itemProps$number.xml"/></Relationships>"""
        val customXml = List(
          item      -> xmlPart.getBytes(UTF_8),
          itemProps -> propsPart.getBytes(UTF_8),
          itemRels  -> relsPart.getBytes(UTF_8)
        )
        val documentRels = relationships(
          byName.get("word/_rels/document.xml.rels"),
          CustomXmlRel,
          s"../customXml/item$number.xml",
          "rIdDmsFields"
        ).map("word/_rels/document.xml.rels" -> _)
        val properties = customProperties(byName.get(PropertiesPart), sorted)
        val rootRels   =
          relationships(byName.get("_rels/.rels"), CustomPropertiesRel, PropertiesPart, "rIdDmsProperties")
            .map("_rels/.rels" -> _)
        val types = contentTypes(
          byName.get("[Content_Types].xml"),
          List(
            s"/$itemProps"      -> "application/vnd.openxmlformats-officedocument.customXmlProperties+xml",
            s"/$PropertiesPart" -> "application/vnd.openxmlformats-officedocument.custom-properties+xml"
          ),
          s"/$item"
        ).map("[Content_Types].xml" -> _)
        customXml ++ documentRels ++ List(PropertiesPart -> properties) ++ rootRels ++ types
    }

  private def isFields(root: Element): Boolean = is(root, Namespace, "fields")

  /** The characters XML 1.0 can carry. */
  private[app] def valid(s: String): String =
    s.filter(c => (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') && c != '\uFFFE' && c != '\uFFFF')

  /** XML text: [[valid]] characters with the markup characters escaped. */
  private[app] def escape(s: String): String =
    valid(s).flatMap {
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case '"' => "&quot;"
      case c   => c.toString
    }

  /** The relationships part with a relationship of `kind` to `target` (added unless there is one). */
  private def relationships(
      existing: Option[Array[Byte]],
      kind: String,
      target: String,
      preferredId: String
  ): Option[Array[Byte]] =
    val doc  = existing.fold(document(s"""<Relationships xmlns="$Relationships"/>"""))(parse)
    val root = doc.getDocumentElement
    val rels = children(root).filter(is(_, Relationships, "Relationship"))
    if rels.exists(r => r.getAttribute("Type") == kind && r.getAttribute("Target") == target) then
      existing.orElse(Some(serialize(doc)))
    else
      val ids = rels.map(_.getAttribute("Id")).toSet
      val id  = Iterator.from(1).map(i => if i == 1 then preferredId else s"$preferredId$i").find(!ids(_)).get
      val rel = doc.createElementNS(Relationships, "Relationship")
      rel.setAttribute("Id", id)
      rel.setAttribute("Type", kind)
      rel.setAttribute("Target", target)
      root.appendChild(rel)
      Some(serialize(doc))

  /** The content types with overrides for the new parts; the custom XML item is covered by a default for
    * `xml`, which is added as override if missing.
    */
  private def contentTypes(
      existing: Option[Array[Byte]],
      overrides: List[(String, String)],
      item: String
  ): Option[Array[Byte]] =
    existing.map { bytes =>
      val doc        = parse(bytes)
      val root       = doc.getDocumentElement
      val entries    = children(root)
      val overriden  = entries.filter(is(_, ContentTypes, "Override")).map(_.getAttribute("PartName")).toSet
      val xmlDefault =
        entries.exists(e =>
          is(e, ContentTypes, "Default") && e.getAttribute("Extension").equalsIgnoreCase("xml")
        )
      val wanted = overrides ++ Option.unless(xmlDefault)(item -> "application/xml")
      wanted.filterNot((part, _) => overriden(part)).foreach { (part, kind) =>
        val o = doc.createElementNS(ContentTypes, "Override")
        o.setAttribute("PartName", part)
        o.setAttribute("ContentType", kind)
        root.appendChild(o)
      }
      serialize(doc)
    }

  /** The custom properties with the `dms.` properties replaced by `values`; other properties are kept. */
  private def customProperties(existing: Option[Array[Byte]], values: List[(String, String)]): Array[Byte] =
    val doc = existing.fold(
      document(s"""<Properties xmlns="$CustomProperties" xmlns:vt="$VariantTypes"/>""")
    )(parse)
    val root  = doc.getDocumentElement
    val props = children(root).filter(is(_, CustomProperties, "property"))
    props
      .filter(p => Option(p.getAttribute("name")).exists(_.startsWith(PropertyPrefix)))
      .foreach(root.removeChild)
    val next = Iterator.from(
      props
        .filterNot(p => Option(p.getAttribute("name")).exists(_.startsWith(PropertyPrefix)))
        .flatMap(p => p.getAttribute("pid").toIntOption)
        .maxOption
        .getOrElse(1) + 1
    )
    values.foreach { (key, value) =>
      val p = doc.createElementNS(CustomProperties, "property")
      p.setAttribute("fmtid", UserDefined)
      p.setAttribute("pid", next.next().toString)
      p.setAttribute("name", PropertyPrefix + key)
      val text = doc.createElementNS(VariantTypes, "vt:lpwstr")
      text.setTextContent(valid(value))
      p.appendChild(text)
      root.appendChild(p)
    }
    serialize(doc)

  private def parse(bytes: Array[Byte]): Document =
    OfficePackage
      .parse(List("part" -> bytes), _ => true, Kind)
      .fold(m => throw IllegalArgumentException(m), _.head._2)

  private def document(xml: String): Document = parse(xml.getBytes(UTF_8))

  private def serialize(doc: Document): Array[Byte] = OfficePackage.serialize(doc)
