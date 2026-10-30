package dms.app

import dms.documents.AttachmentScanner
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** Choice of the virus scanner and the production profile (DMS-117, ADR 0041 addendum). */
object ScannerConfigSpec extends ZIOSpecDefault:

  private def attachments(
      scanner: String,
      allow: Boolean = false,
      host: String = "clamav",
      port: Int = 3310
  ) =
    AttachmentsConfig(50, "", scanner, ClamAvSettings(host, port, 30), allowUnscannedInProduction = allow)

  def spec = suite("Virus scanner configuration")(
    test("none is the development default; production refuses it unless explicitly allowed") {
      assertTrue(
        attachments("none").attachmentScanner(Profile.Development) == Right(AttachmentScanner.None),
        attachments("").attachmentScanner(Profile.Development) == Right(AttachmentScanner.None),
        attachments("none")
          .attachmentScanner(Profile.Production)
          .left
          .exists(_.contains("dms.attachments.scanner = clamav")),
        attachments("none", allow = true).attachmentScanner(Profile.Production) == Right(
          AttachmentScanner.None
        )
      )
    },
    test("clamav selects the ClamAV adapter in every profile; invalid values are refused") {
      assertTrue(
        attachments("ClamAV").attachmentScanner(Profile.Production).exists(_.name == "clamav"),
        attachments("clamav").attachmentScanner(Profile.Development).exists(_.isInstanceOf[ClamAvScanner]),
        attachments("clamav", host = " ").attachmentScanner(Profile.Production).isLeft,
        attachments("clamav", port = 0).attachmentScanner(Profile.Production).isLeft,
        attachments("sophos").attachmentScanner(Profile.Development).left.exists(_.contains("none, clamav")),
        Profile.parse("Production") == Right(Profile.Production),
        Profile.parse("development") == Right(Profile.Development),
        Profile.parse("staging").isLeft
      )
    },
    test("the shipped configuration runs in the development profile without a scanner") {
      // The parts of the shipped application.conf this change adds (the rest needs deployment settings).
      val provider = TypesafeConfigProvider.fromResourcePath()
      for
        profile     <- ZIO.config(Config.string("profile").nested("dms")).withConfigProvider(provider)
        attachments <- ZIO
          .config(deriveConfig[AttachmentsConfig].nested("dms", "attachments"))
          .withConfigProvider(provider)
        cfg = (profile, attachments)
      yield assertTrue(
        cfg._1 == "development",
        cfg._2.scanner == "none",
        cfg._2.clamav.port == 3310,
        !cfg._2.allowUnscannedInProduction,
        cfg._2.attachmentScanner(Profile.Development) == Right(AttachmentScanner.None)
      )
    }
  )
