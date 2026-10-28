package dms.templates

/** Structured template content (ADR 0007): the primary format from which documents and renditions are
  * produced. Plain data, free of effects; the content of a released template version never changes.
  *
  * @param computed
  *   computed fields (business concept 4.2), derived from field values with CEL and usable in placeholders
  *   and expressions like a field; evaluated in order, so a computed field may use the ones before it
  * @param references
  *   reference fields (business concept 4.2): profile fields whose values are taken over from a master data
  *   source after the author picks an entry, e.g. name and address of the contract partner (ADR 0023)
  */
final case class TemplateContent(
    sections: List[Section],
    computed: List[ComputedField] = Nil,
    references: List[ReferenceField] = Nil
)

/** A field derived from other fields, e.g. `totalValue = monthlyFee * termMonths` (CEL, ADR 0020). */
final case class ComputedField(key: String, expression: String)

/** A master data source reference fields can take values from (ADR 0023).
  *
  * @param key
  *   stable name, also the `source` of the stored value origin (`System("partner-master")`)
  * @param attributes
  *   the attributes of an entry that can be taken over; `id` is the entry's identifier in the source
  */
enum ReferenceSource(val key: String, val attributes: List[String]):
  /** The partner master (customers, suppliers, contract partners) of a connected business system. */
  case PartnerMaster
      extends ReferenceSource(
        "partner-master",
        List("id", "name", "street", "postalCode", "city", "country", "address", "vatId")
      )

object ReferenceSource:
  def parse(key: String): Option[ReferenceSource] = values.find(_.key == key)

  /** The attribute holding the identifier of the picked entry; every reference field stores it. */
  val IdAttribute = "id"

/** Takes over `attribute` of the picked entry into the profile field `field`. */
final case class ReferenceMapping(attribute: String, field: String)

/** A reference field of a template (business concept 4.2, US-03.1): the author picks an entry of `source`,
  * and the mapped attributes are taken over into profile fields as system values. Taken-over values are a
  * snapshot: they keep the state of the entry at the time it was picked, and the author cannot edit them,
  * only pick another entry or remove the reference.
  *
  * @param key
  *   name of the reference within the template, e.g. `contractPartner`
  * @param fields
  *   attribute to field mappings; one of them takes over the identifier (`id`) as the source reference
  */
final case class ReferenceField(
    key: String,
    label: String,
    source: ReferenceSource,
    fields: List[ReferenceMapping]
):
  /** The profile fields this reference fills. */
  def fieldKeys: List[String] = fields.map(_.field)

opaque type SectionId = String
object SectionId:
  def apply(value: String): SectionId         = value
  extension (id: SectionId) def value: String = id

/** A section of a template. Authors may change the blocks of an editable section; a locked section holds
  * vetted standard text that authors can only deviate from with a recorded deviation request (business
  * concept 4.1, US-01.1).
  */
final case class Section(id: SectionId, title: Option[String], locked: Boolean, blocks: List[Block])

enum Mark:
  case Bold, Italic, Underline

/** Inline content of headings and paragraphs. */
enum Inline:
  case Text(value: String, marks: Set[Mark] = Set.empty)

  /** Value of a field of the document class profile or of a computed field, e.g. `contractValue`, or of a
    * repeat item, e.g. `item.price`. `format` names a formatter such as `amount`, `date:long`.
    */
  case Placeholder(path: String, format: Option[String] = None)

  case LineBreak

enum Block:
  case Heading(level: Int, content: List[Inline])
  case Paragraph(content: List[Inline])
  case BulletList(items: List[List[Block]])
  case OrderedList(items: List[List[Block]])

  /** Rows of cells; the first `headerRows` rows are table headers. */
  case Table(rows: List[List[List[Block]]], headerRows: Int = 1)

  /** A versioned clause of the clause library, referenced instead of copied (business concept 4.1). */
  case ClauseRef(clauseKey: String, version: Int)

  /** Shown when the CEL `condition` evaluates to true, otherwise `otherwise`. */
  case Conditional(condition: String, body: List[Block], otherwise: List[Block] = Nil)

  /** Repeats `body` for each element of the list field `collection`; inside, the element is `itemName`. */
  case Repeat(collection: String, itemName: String, body: List[Block])

  case PageBreak

object TemplateContent:

  /** All blocks, including nested ones, in document order. */
  def blocks(content: TemplateContent): List[Block] =
    def walk(bs: List[Block]): List[Block] = bs.flatMap { b =>
      b :: (b match
        case Block.BulletList(items)               => items.flatMap(walk)
        case Block.OrderedList(items)              => items.flatMap(walk)
        case Block.Table(rows, _)                  => rows.flatten.flatMap(walk)
        case Block.Conditional(_, body, otherwise) => walk(body) ++ walk(otherwise)
        case Block.Repeat(_, _, body)              => walk(body)
        case _                                     => Nil)
    }
    content.sections.flatMap(s => walk(s.blocks))

  /** All placeholder paths, in document order. */
  def placeholders(content: TemplateContent): List[String] =
    blocks(content)
      .flatMap {
        case Block.Heading(_, inlines) => inlines
        case Block.Paragraph(inlines)  => inlines
        case _                         => Nil
      }
      .collect { case Inline.Placeholder(path, _) => path }

  /** The reference field that fills `field`, with the attribute it takes over, if any. */
  def referenceOf(content: TemplateContent, field: String): Option[(ReferenceField, ReferenceMapping)] =
    content.references.view.flatMap(r => r.fields.find(_.field == field).map(r -> _)).headOption
