package dms.documents.core

import dms.documents.{FieldEntry, MetadataSnapshot, ValueOrigin}
import dms.documents.core.MetadataJson.given
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}
import zio.json.*

/** JSON codecs for document versions, used for the `jsonb` columns of migration V9 and by the HTTP API.
  *
  * Field values:
  * `{"contractPartner": {"value": {"type":"Text","value":"ACME"}, "origin": {"type":"Manual"}}}`; the origin
  * of a system value names its source, e.g. `{"type":"System","source":"crm"}`.
  */
object DocumentJson:
  private given JsonCodecConfiguration =
    JsonCodecConfiguration(sumTypeHandling =
      JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
    )

  given JsonCodec[ValueOrigin]      = DeriveJsonCodec.gen
  given JsonCodec[FieldEntry]       = DeriveJsonCodec.gen
  given JsonCodec[MetadataSnapshot] = DeriveJsonCodec.gen

  /** Word field definitions (DMS-115): `{"key":"customer","label":"Customer","type":"Text","required":true,
    * "source":"Manual"}`.
    */
  given JsonCodec[WordFieldType] = JsonCodec.string.transformOrFail(
    s => WordFieldType.values.find(_.toString == s).toRight(s"unknown field type $s"),
    _.toString
  )
  given JsonCodec[WordFieldSource] = JsonCodec.string.transformOrFail(
    s => WordFieldSource.values.find(_.toString == s).toRight(s"unknown field source $s"),
    _.toString
  )
  given JsonCodec[WordField] = summon[JsonCodec[StoredWordField]].transform(_.toField, StoredWordField.from)

  /** Stored definition; `options`, `columns` and `warning` (DMS-122) are left out when empty, so definitions
    * of earlier fields read and write as before.
    */
  private final case class StoredWordField(
      key: String,
      label: String,
      @jsonField("type") fieldType: WordFieldType,
      required: Boolean,
      source: WordFieldSource,
      options: Option[List[String]] = None,
      columns: Option[List[StoredWordField]] = None,
      warning: Option[String] = None
  ):
    def toField: WordField =
      WordField(
        key,
        label,
        fieldType,
        required,
        source,
        options.getOrElse(Nil),
        columns.getOrElse(Nil).map(_.toField),
        warning
      )

  private object StoredWordField:
    def from(f: WordField): StoredWordField =
      StoredWordField(
        f.key,
        f.label,
        f.fieldType,
        f.required,
        f.source,
        Option.when(f.options.nonEmpty)(f.options),
        Option.when(f.columns.nonEmpty)(f.columns.map(from)),
        f.warning
      )

    given codec: JsonCodec[StoredWordField] = DeriveJsonCodec.gen

  /** The value of a `Rows` Word field (DMS-122): a JSON array of objects with the value of each column by
    * key, e.g. `[{"item":"Pump","quantity":"2"}]`.
    */
  def readRows(value: String): Either[String, WordFields.Rows] =
    value
      .fromJson[List[Map[String, String]]]
      .left
      .map(_ => "a list of rows (JSON array of objects with text values)")

  /** The canonical value of rows: values trimmed, columns without value and rows without any value left out,
    * columns in the order of the definition (so that equal rows give an equal string and checksum); `None`
    * without rows.
    */
  def writeRows(field: WordField, rows: WordFields.Rows): Option[String] =
    val order   = field.columns.map(_.key)
    val cleaned = rows
      .map(_.map((k, v) => k -> v.trim).filter(_._2.nonEmpty))
      .filter(_.nonEmpty)
      .map(row =>
        zio.json.ast.Json.Obj(
          row.toList.sortBy((k, _) => (order.indexOf(k), k)).map((k, v) => k -> zio.json.ast.Json.Str(v))*
        )
      )
    Option.when(cleaned.nonEmpty)(zio.json.ast.Json.Arr(cleaned*).toJson)
