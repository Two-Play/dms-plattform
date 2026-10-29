package dms.documents.core

import dms.documents.{FieldEntry, MetadataSnapshot, ValueOrigin}
import dms.documents.core.MetadataJson.given
import dms.templates.{WordField, WordFieldSource, WordFieldType}
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
  given JsonCodec[WordField] = DeriveJsonCodec.gen[StoredWordField].transform(_.toField, StoredWordField.from)

  private final case class StoredWordField(
      key: String,
      label: String,
      @jsonField("type") fieldType: WordFieldType,
      required: Boolean,
      source: WordFieldSource
  ):
    def toField: WordField = WordField(key, label, fieldType, required, source)

  private object StoredWordField:
    def from(f: WordField): StoredWordField =
      StoredWordField(f.key, f.label, f.fieldType, f.required, f.source)
