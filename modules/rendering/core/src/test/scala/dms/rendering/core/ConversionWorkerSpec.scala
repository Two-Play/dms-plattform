package dms.rendering.core

import dms.rendering.ArchiveStandard
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The conversion worker of `workers/conversion` (DMS-115, ADR 0042), built from its Dockerfile and started
  * with Testcontainers: a Word document becomes a PDF that veraPDF accepts as PDF/A-2b, an HTML page a Word
  * document; unsupported types are refused.
  */
object ConversionWorkerSpec extends ZIOSpecDefault:

  final class Container(image: ImageFromDockerfile) extends GenericContainer[Container](image)

  private def directory: Path =
    val path = Path.of(
      sys.props.getOrElse(
        "dms.conversion.workerDirectory",
        throw IllegalStateException(
          "system property dms.conversion.workerDirectory is not set (see build.sbt)"
        )
      )
    )
    require(Files.isRegularFile(path.resolve("Dockerfile")), s"no Dockerfile in $path")
    path

  /** A started worker; its configuration points at the mapped port. */
  val config: ZLayer[Any, Throwable, ConverterConfig] = ZLayer.scoped(
    ZIO
      .acquireRelease(ZIO.attemptBlocking {
        val image = ImageFromDockerfile("dms-conversion-worker-test", false).withFileFromPath(".", directory)
        val c     = Container(image)
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      .map(c => ConverterConfig(s"http://${c.getHost}:${c.getMappedPort(8080)}"))
  )

  private val word = WordFixtures.docx(
    WordFixtures.paragraph(
      WordFixtures.textRun("Rahmenvertrag über Wartungsleistungen – Fassung für Prüfung")
    ) +
      WordFixtures.paragraph(WordFixtures.control("dms:documentNumber", "Dokumentnummer", "VT-2026-0001"))
  )

  def spec = suite("Conversion worker")(
    test("a Word document becomes a PDF that is valid PDF/A-2b and carries its text") {
      for
        converter <- ZIO.service[DocumentConverter]
        pdf       <- converter.convert(Chunk.fromArray(word), "Vertrag.docx", ConversionTarget.PdfA2b)
        report    <- VeraPdfValidator.validate(pdf.bytes, ArchiveStandard.PdfA2b)
        text = RenderingWorker.normalized(RenderingWorker.pageTexts(pdf.bytes).mkString(" "))
      yield assertTrue(
        DocumentConverter.isPdf(pdf.bytes),
        report.compliant,
        text.contains("Rahmenvertrag über Wartungsleistungen"),
        text.contains("VT-2026-0001"),
        pdf.converter.contains("LibreOffice"),
        // No image reference configured; the build fingerprint is computed by the worker (DMS-93).
        pdf.worker.image.isEmpty,
        pdf.worker.build.exists(_.matches("sha256:[0-9a-f]{64}"))
      )
    },
    test("an HTML page becomes a Word document") {
      for
        converter <- ZIO.service[DocumentConverter]
        docx      <- converter.convert(
          Chunk.fromArray(
            "<html><body><h1>Richtlinie</h1><p>Gültig ab heute.</p></body></html>".getBytes(UTF_8)
          ),
          "page.html",
          ConversionTarget.Docx
        )
        document = WordFixtures.part(docx.bytes.toArray, "word/document.xml")
      yield assertTrue(document.contains("Richtlinie"), document.contains("Gültig ab heute."))
    },
    test("unsupported types are refused") {
      for
        converter <- ZIO.service[DocumentConverter]
        archive   <- converter
          .convert(Chunk.fromArray(Array[Byte](1, 2, 3)), "data.zip", ConversionTarget.PdfA2b)
          .either
        noName <- converter.convert(Chunk.fromArray(word), "contract", ConversionTarget.PdfA2b).either
      yield assertTrue(
        archive.left.exists { case RenderingError.Rejected(_) => true; case _ => false },
        noName.left.exists { case RenderingError.Rejected(_) => true; case _ => false }
      )
    }
  ).provideShared(
    config >>> LibreOfficeConverter.layer
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@
    TestAspect.timeout(10.minutes)
