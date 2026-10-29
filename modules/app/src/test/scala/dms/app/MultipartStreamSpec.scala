package dms.app

import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8

/** The streaming multipart parser of attachment uploads (DMS-114). */
object MultipartStreamSpec extends ZIOSpecDefault:

  private val boundary = "XyZ-boundary"

  private val body =
    s"preamble\r\n--$boundary\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n" +
      s"--$boundary  \r\nContent-Disposition: form-data; name=\"file\"; filename=\"a \\\"b\\\".pdf\"\r\n" +
      s"Content-Type: application/pdf\r\n\r\n%PDF\r\n--not-the-boundary\r\nend\r\n--$boundary--\r\nepilogue"

  private def parse(text: String, chunk: Int) =
    MultipartStream
      .parse(
        ZStream.fromIterable(text.getBytes(UTF_8).grouped(chunk).map(Chunk.fromArray).toList).flattenChunks,
        boundary
      )
      .runCollect

  /** The parts with their data joined, independent of how the data was chunked. */
  private def parts(events: Chunk[MultipartStream.Event]) =
    events
      .foldLeft(List.empty[(MultipartStream.Event.Part, String)]) {
        case (acc, p: MultipartStream.Event.Part)     => acc :+ (p, "")
        case (acc, MultipartStream.Event.Data(bytes)) =>
          acc.init :+ (acc.last._1, acc.last._2 + String(bytes.toArray, UTF_8))
        case (acc, MultipartStream.Event.PartEnd) => acc
      }

  def spec = suite("Multipart stream")(
    test("parts, file names and data are the same however the body is split into chunks") {
      for results <- ZIO.foreach((1 to body.length).toList)(n => parse(body, n).map(parts))
      yield
        val expected = List(
          (MultipartStream.Event.Part("note", None, None), "hello"),
          (
            MultipartStream.Event.Part("file", Some("a \"b\".pdf"), Some("application/pdf")),
            "%PDF\r\n--not-the-boundary\r\nend"
          )
        )
        assertTrue(results.forall(_ == expected))
    },
    test("an RFC 8187 file name is decoded") {
      val text =
        s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.pdf\"; " +
          s"filename*=UTF-8''Gr%C3%BC%C3%9Fe.pdf\r\n\r\ndata\r\n--$boundary--"
      for events <- parse(text, 7)
      yield assertTrue(parts(events).map(_._1.filename) == List(Some("Grüße.pdf")))
    },
    test("truncated bodies and parts without a name are rejected") {
      for
        truncated <- parse(
          s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\nabc",
          4
        ).either
        noName  <- parse(s"--$boundary\r\nContent-Type: text/plain\r\n\r\nabc\r\n--$boundary--", 4).either
        garbage <- parse(s"--${boundary}xx\r\n\r\n", 4).either
      yield assertTrue(
        truncated.left.exists(_.isInstanceOf[MultipartStream.Malformed]),
        noName.left.exists(_.isInstanceOf[MultipartStream.Malformed]),
        garbage.left.exists(_.isInstanceOf[MultipartStream.Malformed])
      )
    }
  )
