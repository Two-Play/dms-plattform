package dms.templates.core

import dms.templates.*
import zio.json.*
import zio.json.ast.Json

import scala.util.Try

/** JSON codecs for the template content model, used for the `jsonb` column and by the HTTP API.
  *
  * Sum types carry their case name in a `type` field, e.g. `{"type":"Paragraph","content":[...]}`. Stored
  * content is wrapped with its format version,
  * `{"format":1,"sections":[...],"computed":[...],"references":[...]}` (`computed` and `references` may be
  * missing in older content and default to empty); a later change of the model adds a format version and a
  * migration from the older ones in [[ContentJson.decodeStored]].
  */
object ContentJson:
  /** The current storage format of [[TemplateContent]]. */
  val FormatVersion: Int = 1

  private given JsonCodecConfiguration =
    JsonCodecConfiguration(sumTypeHandling =
      JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
    )

  given JsonCodec[SectionId] = JsonCodec.string.transformOrFail(
    s => Either.cond(s.trim.nonEmpty, SectionId(s), "a section id must not be empty"),
    _.value
  )
  given JsonCodec[Mark] =
    JsonCodec.string.transformOrFail(
      s => Try(Mark.valueOf(s)).toEither.left.map(_ => s"unknown mark '$s'"),
      _.toString
    )
  given JsonCodec[Inline]          = DeriveJsonCodec.gen
  given JsonCodec[Block]           = DeriveJsonCodec.gen
  given JsonCodec[Section]         = DeriveJsonCodec.gen
  given JsonCodec[ComputedField]   = DeriveJsonCodec.gen
  given JsonCodec[ReferenceSource] = JsonCodec.string.transformOrFail(
    s => ReferenceSource.parse(s).toRight(s"unknown reference source '$s'"),
    _.key
  )
  given JsonCodec[ReferenceMapping] = DeriveJsonCodec.gen
  given JsonCodec[ReferenceField]   = DeriveJsonCodec.gen
  given JsonCodec[TemplateContent]  = DeriveJsonCodec.gen

  given JsonCodec[ResolvedInline]  = DeriveJsonCodec.gen
  given JsonCodec[ResolvedBlock]   = DeriveJsonCodec.gen
  given JsonCodec[ResolvedSection] = DeriveJsonCodec.gen
  given JsonCodec[ResolvedContent] = DeriveJsonCodec.gen

  /** The stored form, with its format version. */
  def encodeStored(content: TemplateContent): String =
    content.toJsonAST match
      case Right(Json.Obj(fields)) => Json.Obj(("format" -> Json.Num(FormatVersion)) +: fields).toJson
      case other => throw IllegalStateException(s"content did not encode to an object: $other")

  def decodeStored(json: String): Either[String, TemplateContent] =
    json.fromJson[Json.Obj].flatMap { obj =>
      obj.get("format").flatMap(_.as[Int].toOption) match
        case Some(FormatVersion) => Json.Obj(obj.fields.filterNot(_._1 == "format")).as[TemplateContent]
        case Some(other)         => Left(s"unknown content format $other")
        case None                => Left("the content has no format version")
    }
