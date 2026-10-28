package dms.documents.core

import dms.documents.DocumentStatus
import dms.documents.metadata.*
import zio.json.*

import scala.util.Try

/** JSON codecs for the metadata model, used for `jsonb` columns and by the HTTP API.
  *
  * Sum types carry their case name in a `type` field, e.g. `{"type":"Text","maxLength":200}`.
  */
object MetadataJson:
  private given JsonCodecConfiguration =
    JsonCodecConfiguration(sumTypeHandling =
      JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
    )

  given JsonCodec[DocumentStatus] =
    JsonCodec.string.transformOrFail(
      s => Try(DocumentStatus.valueOf(s)).toEither.left.map(_ => s"unknown status '$s'"),
      _.toString
    )

  given JsonCodec[FieldKey] = JsonCodec.string.transformOrFail(FieldKey.parse, _.value)

  given JsonCodec[SelectOption]  = DeriveJsonCodec.gen
  given JsonCodec[FieldType]     = DeriveJsonCodec.gen
  given JsonCodec[FieldBinding]  = DeriveJsonCodec.gen
  given JsonCodec[MetadataValue] = DeriveJsonCodec.gen
