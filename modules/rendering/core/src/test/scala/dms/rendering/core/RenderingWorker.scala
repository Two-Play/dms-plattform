package dms.rendering.core

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import zio.*

import java.nio.file.{Files, Path}

/** The rendering worker of `workers/rendering` (ADR 0025), built from its Dockerfile and started with
  * Testcontainers. The image is built once per Docker daemon and then comes from the layer cache.
  */
object RenderingWorker:

  final class Container(image: ImageFromDockerfile) extends GenericContainer[Container](image)

  /** `workers/rendering`, passed by the build as a system property (`dms.rendering.workerDirectory`). */
  def directory: Path =
    val path = Path.of(
      sys.props.getOrElse(
        "dms.rendering.workerDirectory",
        throw IllegalStateException(
          "system property dms.rendering.workerDirectory is not set (see build.sbt)"
        )
      )
    )
    require(Files.isRegularFile(path.resolve("Dockerfile")), s"no Dockerfile in $path")
    path

  /** A started worker; its configuration points at the mapped port. */
  val config: ZLayer[Any, Throwable, RendererConfig] = ZLayer.scoped(
    ZIO
      .acquireRelease(ZIO.attemptBlocking {
        val image = ImageFromDockerfile("dms-rendering-worker-test", false).withFileFromPath(".", directory)
        val c     = Container(image)
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      .map(c => RendererConfig(s"http://${c.getHost}:${c.getMappedPort(8080)}"))
  )

  val renderer: ZLayer[Any, Throwable, PdfRenderer] = config >>> WeasyPrintRenderer.layer

  /** The text of every page, first page first (PDFBox). */
  def pageTexts(pdf: Chunk[Byte]): List[String] =
    val document = Loader.loadPDF(pdf.toArray)
    try
      (1 to document.getNumberOfPages).toList.map { page =>
        val stripper = PDFTextStripper()
        stripper.setStartPage(page)
        stripper.setEndPage(page)
        stripper.getText(document)
      }
    finally document.close()

  /** Whitespace collapsed to single spaces, so checks do not depend on line breaks inside margin boxes. */
  def normalized(text: String): String = text.replaceAll("\\s+", " ").trim
