package dms.documents

import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}

object ValiditySpec extends ZIOSpecDefault:

  private val berlin = ZoneId.of("Europe/Berlin")

  def spec = suite("Validity")(
    test("a version is in force from 00:00 of its valid-from date, but never before its publication") {
      val published = Instant.parse("2026-12-31T23:00:02Z")
      assertTrue(
        // Published by the job a moment after 00:00: in force from the publication.
        Validity.effectiveStart(Some(LocalDate.of(2027, 1, 1)), published, berlin) == published,
        // Published late (approved after the date): not retroactive.
        Validity.effectiveStart(Some(LocalDate.of(2026, 6, 1)), published, berlin) == published,
        // Recorded before its date (data from before scheduled publication): from 00:00 of the date.
        Validity.effectiveStart(Some(LocalDate.of(2027, 2, 1)), published, berlin) ==
          Instant.parse("2027-01-31T23:00:00Z"),
        Validity.effectiveStart(None, published, berlin) == published
      )
    },
    test("a period contains its start but not its end") {
      val start  = Instant.parse("2026-01-01T00:00:00Z")
      val end    = Instant.parse("2026-02-01T00:00:00Z")
      val period = ValidityPeriod(start, Some(end))
      assertTrue(
        period.contains(start),
        period.contains(end.minusNanos(1000)),
        !period.contains(end),
        !period.contains(start.minusNanos(1000)),
        ValidityPeriod(start, None).contains(Instant.parse("2100-01-01T00:00:00Z"))
      )
    },
    test("a date means its last moment in the tenant's time zone") {
      assertTrue(
        Validity.endOfDay(LocalDate.of(2027, 1, 15), berlin) == Instant.parse("2027-01-15T22:59:59.999999Z"),
        // Daylight saving time starts on 29 March 2026 in Berlin.
        Validity.endOfDay(LocalDate.of(2026, 3, 29), berlin) == Instant.parse("2026-03-29T21:59:59.999999Z")
      )
    }
  )
