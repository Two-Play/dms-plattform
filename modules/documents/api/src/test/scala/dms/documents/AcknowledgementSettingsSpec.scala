package dms.documents

import zio.test.*

import java.time.Instant
import java.util.UUID

/** Pure rules of acknowledgement settings and reminders (DMS-100, ADR 0048). */
object AcknowledgementSettingsSpec extends ZIOSpecDefault:
  private val published = Instant.parse("2026-11-02T09:00:00Z")
  private val parent    = UUID.randomUUID()
  private val child     = UUID.randomUUID()
  private val hr        = AcknowledgementRecipients(units = Set("HR"))

  def spec = suite("Acknowledgement settings")(
    test("the nearest class with settings applies; an open reminder interval comes from the tenant") {
      val tenant = AcknowledgementSettings(AcknowledgementRecipients(roles = Set("Reader")), Some(7))
      val own    = AcknowledgementSettings(hr, None)
      assertTrue(
        VersionAudience.resolve(List(child -> own, parent -> AcknowledgementSettings()), Some(tenant)) ==
          (AudienceSource.Class, Some(child), AcknowledgementSettings(hr, Some(7))),
        VersionAudience.resolve(List(parent -> AcknowledgementSettings(hr, Some(3))), Some(tenant)) ==
          (AudienceSource.Class, Some(parent), AcknowledgementSettings(hr, Some(3))),
        VersionAudience.resolve(Nil, Some(tenant)) == (AudienceSource.Tenant, None, tenant),
        VersionAudience.resolve(Nil, None) == (AudienceSource.Default, None, AcknowledgementSettings()),
        AcknowledgementSettings().recipients.everyReader
      )
    },
    test("reminder intervals and lists are bounded") {
      assertTrue(
        AcknowledgementSettings(reminderDays = Some(0)).problems.isEmpty,
        AcknowledgementSettings(reminderDays = Some(365)).problems.isEmpty,
        AcknowledgementSettings(reminderDays = Some(-1)).problems.nonEmpty,
        AcknowledgementSettings(reminderDays = Some(366)).problems.nonEmpty,
        AcknowledgementSettings(AcknowledgementRecipients(units = Set(" HR"))).problems.nonEmpty,
        AcknowledgementSettings(
          AcknowledgementRecipients(groups = (1 to 51).map(i => s"g$i").toSet)
        ).problems.nonEmpty
      )
    },
    test("an automatic reminder is due after each whole interval, at most three times") {
      def at(days: Int, hours: Int = 0) = published.plusSeconds(days * 86400L + hours * 3600L)
      assertTrue(
        AcknowledgementReminders.dueRound(published, at(13, 23), 14).isEmpty,
        AcknowledgementReminders.dueRound(published, at(14), 14) == Some(1),
        AcknowledgementReminders.dueRound(published, at(27), 14) == Some(1),
        AcknowledgementReminders.dueRound(published, at(28), 14) == Some(2),
        AcknowledgementReminders.dueRound(published, at(42), 14) == Some(3),
        AcknowledgementReminders.dueRound(published, at(56), 14).isEmpty,
        AcknowledgementReminders.dueRound(published, at(100), 0).isEmpty,
        AcknowledgementReminders.dueRound(published, published.minusSeconds(60), 1).isEmpty
      )
    }
  )
