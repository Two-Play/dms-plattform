package dms.templates.core

import dms.kernel.UserId
import dms.templates.*
import dms.templates.core.ContentJson.given
import zio.json.*
import zio.json.ast.Json

/** JSON codecs for the deviation report (DMS-36), used for the `deviation_report` column of document versions
  * (migration V12) and by the HTTP API.
  *
  * Sum types carry their case name in a `type` field like the content, e.g.
  * `{"type":"TextReplaced","from":"30 days","to":"60 days"}` or `{"type":"Cell","row":1,"column":0}`. Stored
  * reports are wrapped with their format version, `{"format":1,"deviations":[...]}`; inserted and deleted
  * blocks use the content format of [[ContentJson]].
  */
object DeviationJson:
  /** The current storage format of [[DeviationReport]]. */
  val FormatVersion: Int = 1

  private given JsonCodecConfiguration =
    JsonCodecConfiguration(sumTypeHandling =
      JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
    )

  given JsonCodec[UserId]             = JsonCodec.string.transform(UserId(_), _.value)
  given JsonCodec[Inline.Placeholder] = DeriveJsonCodec.gen
  given JsonCodec[PathStep]           = DeriveJsonCodec.gen
  given JsonCodec[DeviationLocation]  = DeriveJsonCodec.gen
  given JsonCodec[DeviationChange]    = DeriveJsonCodec.gen
  given JsonCodec[DeviationRequest]   = DeriveJsonCodec.gen
  given JsonCodec[Deviation]          = DeriveJsonCodec.gen
  given JsonCodec[DeviationReport]    = DeriveJsonCodec.gen

  /** The stored form, with its format version. */
  def encodeStored(report: DeviationReport): String =
    Json
      .Obj(
        "format"     -> Json.Num(FormatVersion),
        "deviations" -> report.deviations.toJsonAST.fold(e => throw IllegalStateException(e), identity)
      )
      .toJson

  def decodeStored(json: String): Either[String, DeviationReport] =
    json.fromJson[Json.Obj].flatMap { obj =>
      obj.get("format").flatMap(_.as[Int].toOption) match
        case Some(FormatVersion) =>
          obj.get("deviations").getOrElse(Json.Arr()).as[List[Deviation]].map(DeviationReport(_))
        case Some(other) => Left(s"unknown deviation report format $other")
        case None        => Left("the deviation report has no format version")
    }
end DeviationJson
