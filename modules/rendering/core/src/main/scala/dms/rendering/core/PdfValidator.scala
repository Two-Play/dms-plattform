package dms.rendering.core

import dms.rendering.ArchiveStandard
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.pdfa.results.TestAssertion
import zio.*

import java.io.ByteArrayInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Result of validating a PDF against an archive standard.
  *
  * @param failures
  *   failed rules as `clause-test: message`, without duplicates
  */
final case class ValidationReport(
    standard: ArchiveStandard,
    compliant: Boolean,
    validator: String,
    failures: List[String]
)

/** Validates PDFs against an archive standard (ADR 0008, 0025). */
trait PdfValidator:
  def validate(pdf: Chunk[Byte], standard: ArchiveStandard): IO[RenderingError, ValidationReport]

/** veraPDF, the PDF Association's reference validator, in-process with its Greenfield parser. */
object VeraPdfValidator extends PdfValidator:

  /** veraPDF's release of the library on the classpath. */
  val name: String =
    "veraPDF " + Option(classOf[Foundries].getPackage.getImplementationVersion).getOrElse("1.30")

  private lazy val initialised: Unit = VeraGreenfieldFoundryProvider.initialise()

  def validate(pdf: Chunk[Byte], standard: ArchiveStandard): IO[RenderingError, ValidationReport] =
    ZIO
      .attemptBlocking {
        initialised
        val flavour = PDFAFlavour.fromString(standard.flavour)
        val foundry = Foundries.defaultInstance()
        Using.Manager { use =>
          val parser    = use(foundry.createParser(ByteArrayInputStream(pdf.toArray), flavour))
          val validator = use(foundry.createValidator(flavour, false))
          val result    = validator.validate(parser)
          val failures  = result.getTestAssertions.asScala.toList
            .filter(_.getStatus == TestAssertion.Status.FAILED)
            .map(a => s"${a.getRuleId.getClause}-${a.getRuleId.getTestNumber}: ${a.getMessage}")
            .distinct
          ValidationReport(standard, result.isCompliant, name, failures)
        }.get
      }
      // A file veraPDF cannot even parse is not archivable either.
      .catchAll(e =>
        ZIO.succeed(
          ValidationReport(standard, compliant = false, name, List(s"cannot be parsed: ${e.getMessage}"))
        )
      )

  val layer: ULayer[PdfValidator] = ZLayer.succeed(VeraPdfValidator)
