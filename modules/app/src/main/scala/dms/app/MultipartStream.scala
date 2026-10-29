package dms.app

import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets

/** A streaming parser for `multipart/form-data` (RFC 7578) that never holds more than one network chunk plus
  * a few bytes of look-ahead in memory, whatever the size of a part (DMS-114).
  *
  * zio-http's own form parser buffers parts without a binary content type in memory and does not end a part's
  * stream when the body fails half-way; for uploads of up to tens of megabytes from any client, a small
  * parser of our own is simpler to reason about. Malformed bodies fail the stream with
  * [[MultipartStream.Malformed]].
  */
object MultipartStream:

  enum Event:
    /** The headers of a part. */
    case Part(name: String, filename: Option[String], contentType: Option[String])

    /** Bytes of the current part, in order. */
    case Data(bytes: Chunk[Byte])

    /** The current part is complete. */
    case PartEnd

  final class Malformed(message: String) extends RuntimeException(message)

  /** Largest accepted header block of one part. */
  val MaxHeaderBytes: Int = 16 * 1024

  def parse(body: ZStream[Any, Throwable, Byte], boundary: String): ZStream[Any, Throwable, Event] =
    ZStream.unwrap(ZIO.succeed {
      val parser = Parser(boundary)
      body.chunks.mapZIO(c => ZIO.fromEither(parser.feed(c))).flattenChunks ++
        ZStream.fromZIO(ZIO.fromEither(parser.finish())).flattenChunks
    })

  private enum State:
    case Preamble, AfterDelimiter, Headers, Body, Epilogue

  private final class Parser(boundary: String):
    private val delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII)
    private val keep      = delimiter.length - 1
    // A leading CRLF lets the first delimiter, which has none in front of it, be found like the others.
    private var buffer: Array[Byte] = "\r\n".getBytes(StandardCharsets.US_ASCII)
    private var state: State        = State.Preamble

    def feed(chunk: Chunk[Byte]): Either[Throwable, Chunk[Event]] =
      if state == State.Epilogue then Right(Chunk.empty)
      else
        buffer = Array.concat(buffer, chunk.toArray)
        step(Chunk.newBuilder[Event])

    def finish(): Either[Throwable, Chunk[Event]] =
      if state == State.Epilogue then Right(Chunk.empty)
      else Left(Malformed("the multipart body ended before its closing boundary"))

    private def step(out: ChunkBuilder[Event]): Either[Throwable, Chunk[Event]] =
      var progress = true
      var failure  = Option.empty[Throwable]
      while progress && failure.isEmpty do
        state match
          case State.Preamble =>
            val i = indexOf(buffer, delimiter, 0)
            if i >= 0 then
              drop(i + delimiter.length)
              state = State.AfterDelimiter
            else
              drop((buffer.length - keep).max(0))
              progress = false

          case State.AfterDelimiter =>
            // Transport padding (spaces, tabs) may follow a delimiter.
            val padding = buffer.takeWhile(b => b == ' ' || b == '\t').length
            drop(padding)
            if buffer.length < 2 then progress = false
            else if buffer(0) == '-' && buffer(1) == '-' then
              state = State.Epilogue
              buffer = Array.emptyByteArray
              progress = false
            else if buffer(0) == '\r' && buffer(1) == '\n' then
              drop(2)
              state = State.Headers
            else failure = Some(Malformed("a multipart boundary is followed by unexpected bytes"))

          case State.Headers =>
            val bounds =
              if buffer.length >= 2 && buffer(0) == '\r' && buffer(1) == '\n' then Some((0, 2))
              else Option(indexOf(buffer, HeaderEnd, 0)).filter(_ >= 0).map(i => (i, i + HeaderEnd.length))
            bounds match
              case Some((headerEnd, bodyStart)) =>
                val text = String(buffer, 0, headerEnd, StandardCharsets.UTF_8)
                drop(bodyStart)
                part(text) match
                  case Right(p) =>
                    out += p
                    state = State.Body
                  case Left(e) => failure = Some(e)
              case None if buffer.length > MaxHeaderBytes =>
                failure = Some(Malformed("the headers of a multipart part are too large"))
              case None => progress = false

          case State.Body =>
            val i = indexOf(buffer, delimiter, 0)
            if i >= 0 then
              if i > 0 then out += Event.Data(Chunk.fromArray(java.util.Arrays.copyOfRange(buffer, 0, i)))
              out += Event.PartEnd
              drop(i + delimiter.length)
              state = State.AfterDelimiter
            else
              val emit = buffer.length - keep
              if emit > 0 then
                out += Event.Data(Chunk.fromArray(java.util.Arrays.copyOfRange(buffer, 0, emit)))
                drop(emit)
              progress = false

          case State.Epilogue =>
            buffer = Array.emptyByteArray
            progress = false
      failure.toLeft(out.result())

    private def drop(n: Int): Unit =
      buffer =
        if n <= 0 then buffer else java.util.Arrays.copyOfRange(buffer, n.min(buffer.length), buffer.length)

  private val HeaderEnd: Array[Byte] = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)

  private def indexOf(data: Array[Byte], pattern: Array[Byte], from: Int): Int =
    val last = data.length - pattern.length
    var i    = from
    var hit  = -1
    while hit < 0 && i <= last do
      var j = 0
      while j < pattern.length && data(i + j) == pattern(j) do j += 1
      if j == pattern.length then hit = i else i += 1
    hit

  /** The part described by a header block: its name and file name from Content-Disposition, its media type.
    */
  private def part(headers: String): Either[Throwable, Event.Part] =
    val fields = headers
      .split("\r\n")
      .toList
      .filter(_.nonEmpty)
      .map { line =>
        val colon = line.indexOf(':')
        if colon <= 0 then ("", line)
        else (line.substring(0, colon).trim.toLowerCase, line.substring(colon + 1).trim)
      }
      .toMap
    fields.get("content-disposition").map(parameters) match
      case Some((kind, params)) if kind.equalsIgnoreCase("form-data") && params.contains("name") =>
        val filename = params.get("filename*").flatMap(extendedValue).orElse(params.get("filename"))
        Right(Event.Part(params("name"), filename, fields.get("content-type").filter(_.nonEmpty)))
      case _ => Left(Malformed("a multipart part has no Content-Disposition form-data name"))

  /** `form-data; name="file"; filename="a.pdf"` as the type and its parameters (names lowercase). */
  private def parameters(value: String): (String, Map[String, String]) =
    val parts   = List.newBuilder[String]
    val current = StringBuilder()
    var quoted  = false
    var escaped = false
    value.foreach { c =>
      if escaped then
        current += c
        escaped = false
      else if quoted && c == '\\' then escaped = true
      else if c == '"' then quoted = !quoted
      else if c == ';' && !quoted then
        parts += current.result()
        current.clear()
      else current += c
    }
    parts += current.result()
    parts.result().map(_.trim) match
      case kind :: params =>
        (
          kind,
          params.flatMap { p =>
            val eq = p.indexOf('=')
            Option.when(eq > 0)(p.substring(0, eq).trim.toLowerCase -> p.substring(eq + 1).trim)
          }.toMap
        )
      case Nil => ("", Map.empty)

  /** An RFC 8187 value `UTF-8''n%C3%A4me.pdf`. */
  private def extendedValue(value: String): Option[String] =
    val i = value.indexOf("''")
    Option
      .when(i > 0 && value.substring(0, i).equalsIgnoreCase("UTF-8"))(value.substring(i + 2))
      .flatMap(encoded =>
        scala.util.Try(java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")).toOption
      )
