package dms.documents.core

import dms.documents.{FieldEntry, MetadataSnapshot, ValueOrigin}
import dms.documents.core.MetadataJson.given
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
