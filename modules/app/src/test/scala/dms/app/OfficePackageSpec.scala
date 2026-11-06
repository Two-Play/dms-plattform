package dms.app

import dms.rendering.core.WordFixtures.*
import zio.*
import zio.test.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.{ZipEntry, ZipOutputStream}

/** Limits on reading Office files (DMS-160, ADR 0076): a small file must not be able to take the memory of
  * the platform, and with it the requests of every other tenant.
  */
object OfficePackageSpec extends ZIOSpecDefault:

  private val MiB = 1024 * 1024

  private def pack(parts: (String, Array[Byte])*): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    val zip   = ZipOutputStream(bytes)
    parts.foreach { (name, data) =>
      zip.putNextEntry(ZipEntry(name))
      zip.write(data)
      zip.closeEntry()
    }
    zip.close()
    bytes.toByteArray

  /** XML of about `size` bytes that deflate cannot shrink much. */
  private def noise(size: Int): Array[Byte] =
    val random = java.util.Random(size.toLong)
    val text   = StringBuilder("<x>")
    while text.length < size do text.append(java.lang.Long.toString(random.nextLong(), 36))
    text.append("</x>").toString.getBytes(UTF_8)

  private def tooComplex[A](result: Either[OfficeProblem, A], text: String) =
    assertTrue(result.left.exists {
      case OfficeProblem.TooComplex(m) => m.contains(text)
      case _                           => false
    })

  private val limits = suite("limits")(
    test("the reported file is refused: a small DOCX that unpacks to hundreds of MiB") {
      val spaces = Array.fill[Byte](267 * MiB)(' ')
      val bomb   = pack("word/document.xml" -> ("<x>".getBytes(UTF_8) ++ spaces ++ "</x>".getBytes(UTF_8)))
      assertTrue(bomb.length < 2 * MiB) && tooComplex(WordDocx.scan(bomb), "packed too densely")
    },
    test("one part above the limit is refused, even if it is not packed densely") {
      val file = pack("word/document.xml" -> noise(33 * MiB))
      tooComplex(WordDocx.scan(file), "MiB")
    },
    test("the limit holds for all parts of a file together, not for each") {
      val file = pack(
        ("word/document.xml" -> "<x/>".getBytes(UTF_8)) ::
          (1 to 9).toList.map(i => s"word/header$i.xml" -> noise(4 * MiB))*
      )
      tooComplex(WordDocx.scan(file), "MiB")
    },
    test("large parts that are not parsed, such as images, do not count") {
      val file = pack(
        "word/document.xml"     -> "<x/>".getBytes(UTF_8),
        "word/media/image1.png" -> noise(40 * MiB)
      )
      assertTrue(WordDocx.scan(file).isRight)
    },
    test("a part below the limit is read, however repetitive") {
      val row  = "<w:p><w:r><w:t>Same text in every paragraph</w:t></w:r></w:p>"
      val body = paragraph(textRun("{{customer}}")) + row * 100000
      assertTrue(WordDocx.scan(docx(body)).exists(_.map(_.key) == List("customer")))
    },
    test("elements nested too deeply are refused, not left to overflow the stack") {
      val deep = ("<a>" * 200000 + "</a>" * 200000).getBytes(UTF_8)
      tooComplex(WordDocx.scan(pack("word/document.xml" -> deep)), "nested")
    },
    test("nesting a real document can have is read") {
      val deep = ("<a>" * 300 + "</a>" * 300).getBytes(UTF_8)
      assertTrue(WordDocx.scan(pack("word/document.xml" -> deep)).isRight)
    },
    test("a broken file stays unreadable and is not called too complex") {
      assertTrue(WordDocx.scan("not a zip".getBytes(UTF_8)).left.exists {
        case OfficeProblem.Unreadable(_) => true
        case _                           => false
      })
    },
    test("the refusal has its own error code; unreadable files keep theirs") {
      val refused = OfficeProblem.response("invalid_docx")(OfficeProblem.TooComplex("too much"))
      val broken  = OfficeProblem.response("invalid_docx")(OfficeProblem.Unreadable("broken"))
      assertTrue(refused._2.code == "office_file_too_complex", broken._2.code == "invalid_docx")
    }
  )

  private val gate = suite("gate")(
    test("running out of memory or stack refuses the file and leaves the process alone") {
      for
        memory <- OfficeGate[Unit](throw OutOfMemoryError("Java heap space")).either
        stack  <- OfficeGate[Unit](throw StackOverflowError()).either
        after  <- OfficeGate(Right(1))
      yield assertTrue(
        memory.left.exists(_.isInstanceOf[OfficeProblem.TooComplex]),
        stack.left.exists(_.isInstanceOf[OfficeProblem.TooComplex]),
        after == 1
      )
    },
    test("only so many files are read at once, the others wait their turn") {
      val running = AtomicInteger()
      val peak    = AtomicInteger()
      for done <- ZIO.foreachPar((1 to OfficeGate.Permits * 3).toList)(_ =>
          OfficeGate {
            peak.accumulateAndGet(running.incrementAndGet(), math.max)
            Thread.sleep(50)
            running.decrementAndGet()
            Right(1)
          }
        )
      yield assertTrue(done.sum == OfficeGate.Permits * 3, peak.get <= OfficeGate.Permits)
    },
    test("reading happens away from the threads that run requests") {
      for
        request <- ZIO.succeed(Thread.currentThread.getName)
        read    <- OfficeGate(Right(Thread.currentThread.getName))
      yield assertTrue(read != request, read.contains("blocking"))
    }
  )

  def spec =
    suite("Limits on Office files")(limits, gate) @@ TestAspect.withLiveClock @@ TestAspect.sequential
