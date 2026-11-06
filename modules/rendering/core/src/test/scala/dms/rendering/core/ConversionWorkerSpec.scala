package dms.rendering.core

import dms.rendering.ArchiveStandard
import com.github.dockerjava.api.command.CreateContainerCmd
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import java.util.function.Consumer
import java.util.zip.{ZipEntry, ZipOutputStream}

/** The conversion worker of `workers/conversion` (DMS-115, ADR 0042), built from its Dockerfile and started
  * with Testcontainers: a Word document becomes a PDF that veraPDF accepts as PDF/A-2b, an HTML page a Word
  * document; unsupported types are refused. External content in documents is not loaded (DMS-160, ADR 0076):
  * a second container on the worker's network records what is requested from it.
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

  /** A started worker, with what the other container on its network was asked for. */
  final case class Worker(config: ConverterConfig, requested: () => String)

  private val worker: ZLayer[Any, Throwable, Worker] = ZLayer.scoped(
    for
      network <- ZIO.acquireRelease(ZIO.attemptBlocking(Network.newNetwork()))(n =>
        ZIO.attemptBlocking(n.close()).orDie
      )
      image = ImageFromDockerfile("dms-conversion-worker-test", false).withFileFromPath(".", directory)
      listener <- start {
        val entrypoint: Consumer[CreateContainerCmd] =
          _.withEntrypoint("python", "-u", "-m", "http.server", "8000", "--directory", "/tmp"): Unit
        Container(image)
          .withNetwork(network)
          .withNetworkAliases("listener")
          .withExposedPorts(8000)
          .withCreateContainerCmdModifier(entrypoint)
          .waitingFor(Wait.forListeningPort())
      }
      c <- start(
        Container(image)
          .withNetwork(network)
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
      )
    yield Worker(ConverterConfig(s"http://${c.getHost}:${c.getMappedPort(8080)}"), () => listener.getLogs)
  )

  private def start(container: => Container) =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val c = container
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)

  /** Its configuration points at the mapped port. */
  val config: ZLayer[Any, Throwable, ConverterConfig] = worker.project(_.config)

  private def imageCount(pdf: Chunk[Byte]): Int =
    "/Subtype\\s*/Image".r.findAllIn(String(pdf.toArray, java.nio.charset.StandardCharsets.ISO_8859_1)).size

  private def html(head: String, body: String) =
    Chunk.fromArray(s"<html><head>$head</head><body><p>Text</p>$body</body></html>".getBytes(UTF_8))

  private def zip(parts: (String, String)*): Chunk[Byte] =
    val bytes = ByteArrayOutputStream()
    val out   = ZipOutputStream(bytes)
    parts.foreach { (name, content) =>
      out.putNextEntry(ZipEntry(name))
      out.write(content.getBytes(UTF_8))
      out.closeEntry()
    }
    out.close()
    Chunk.fromArray(bytes.toByteArray)

  /** An OpenDocument text with one linked image. */
  private def odt(href: String) =
    val mime = "application/vnd.oasis.opendocument.text"
    zip(
      "mimetype"              -> mime,
      "META-INF/manifest.xml" ->
        s"""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
           |<manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/>
           |<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
           |</manifest:manifest>""".stripMargin,
      "content.xml" ->
        s"""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
           | xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
           | xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
           | xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
           | xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.2"><office:body><office:text>
           |<text:p>Text<draw:frame svg:width="5cm" svg:height="5cm"><draw:image xlink:href="$href"
           | xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></text:p>
           |</office:text></office:body></office:document-content>""".stripMargin
    )

  /** A Word document with one image linked with `r:link`. */
  private def linkedDocx(href: String) =
    val pictures = "http://schemas.openxmlformats.org/drawingml/2006"
    val office   = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    val package_ = "http://schemas.openxmlformats.org/package/2006"
    zip(
      "[Content_Types].xml" ->
        s"""<Types xmlns="$package_/content-types">
           |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
           |<Default Extension="xml" ContentType="application/xml"/>
           |<Override PartName="/word/document.xml" ContentType=
           |"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
           |</Types>""".stripMargin,
      "_rels/.rels" ->
        s"""<Relationships xmlns="$package_/relationships"><Relationship Id="rId1"
           | Type="$office/officeDocument" Target="word/document.xml"/></Relationships>""".stripMargin,
      "word/_rels/document.xml.rels" ->
        s"""<Relationships xmlns="$package_/relationships"><Relationship Id="rId5"
           | Type="$office/image" Target="$href" TargetMode="External"/></Relationships>""".stripMargin,
      "word/document.xml" ->
        s"""<w:document xmlns:w="${WordFixtures.W}" xmlns:r="$office" xmlns:wp="$pictures/wordprocessingDrawing"
           | xmlns:a="$pictures/main" xmlns:pic="$pictures/picture"><w:body><w:p><w:r><w:t>Text</w:t></w:r><w:r>
           |<w:drawing><wp:inline><wp:extent cx="952500" cy="952500"/><wp:docPr id="1" name="p"/><a:graphic>
           |<a:graphicData uri="$pictures/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="p"/><pic:cNvPicPr/>
           |</pic:nvPicPr><pic:blipFill><a:blip r:link="rId5"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
           |<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="952500" cy="952500"/></a:xfrm>
           |<a:prstGeom prst="rect"/></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>
           |</w:r></w:p></w:body></w:document>""".stripMargin
    )

  /** A file in the image, readable by the worker. */
  private val LocalImage = "file:///usr/lib/libreoffice/program/intro.png"

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
    test(
      "images linked from documents are not fetched; a stylesheet is, which is why the network is closed"
    ) {
      def pdf(bytes: Chunk[Byte], name: String) =
        ZIO.serviceWithZIO[DocumentConverter](_.convert(bytes, name, ConversionTarget.PdfA2b))
      for
        requested <- ZIO.serviceWith[Worker](_.requested)
        _         <- pdf(html("", """<img src="http://listener:8000/html.png">"""), "page.html")
        _         <- pdf(odt("http://listener:8000/odt.png"), "text.odt")
        _         <- pdf(linkedDocx("http://listener:8000/docx.png"), "word.docx")
        // Reached the listener: the absence of the images above means something. If a later LibreOffice stops
        // this as well, keep the network rule and turn this around.
        _ <- pdf(html("""<link rel="stylesheet" href="http://listener:8000/page.css">""", ""), "page.html")
        log = requested()
      yield assertTrue(
        log.contains("/page.css"),
        !log.contains("/html.png"),
        !log.contains("/odt.png"),
        !log.contains("/docx.png")
      )
    },
    test("images read through file:// are not embedded, images inside the document are") {
      val tiny =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
      val inside = s"""<img src="data:image/png;base64,$tiny" width="50" height="50">"""
      def pdf(bytes: Chunk[Byte], name: String) =
        ZIO
          .serviceWithZIO[DocumentConverter](_.convert(bytes, name, ConversionTarget.PdfA2b))
          .map(_.bytes)
      for
        page <- pdf(html("", s"""<img src="$LocalImage">"""), "page.html")
        text <- pdf(odt(LocalImage), "text.odt")
        word <- pdf(linkedDocx(LocalImage), "word.docx")
        data <- pdf(html("", inside), "page.html")
      yield assertTrue(
        imageCount(page) == 0,
        imageCount(text) == 0,
        imageCount(word) == 0,
        imageCount(data) > 0
      )
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
    worker >+> (worker.project(_.config) >>> LibreOfficeConverter.layer)
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@
    TestAspect.timeout(10.minutes)
