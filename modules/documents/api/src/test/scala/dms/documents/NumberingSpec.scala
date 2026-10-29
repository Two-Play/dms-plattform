package dms.documents

import zio.test.*

import java.time.Instant
import java.util.UUID

object NumberingSpec extends ZIOSpecDefault:
  import NumberToken.*

  private def parsed(pattern: String): NumberPattern =
    NumberPattern.parse(pattern).fold(e => throw AssertionError(e.mkString("; ")), identity)

  private def range(pattern: String, start: Long = 1, yearly: Boolean = true) =
    NumberRange(
      UUID.randomUUID(),
      "r",
      "Range",
      parsed(pattern),
      start,
      yearly,
      "admin",
      Instant.EPOCH,
      Instant.EPOCH
    )

  def spec = suite("Number ranges (DMS-113)")(
    suite("pattern parser")(
      test("prefix, year, unit and counter with digits") {
        assertTrue(
          parsed("POL-{YYYY}-{NNNN}").tokens == List(Literal("POL-"), Year, Literal("-"), Counter(4)),
          parsed("{OU}_x{YY}{N}").tokens == List(Unit, Literal("_x"), ShortYear, Counter(1)),
          parsed("  RL-{NNN}  ").show == "RL-{NNN}",
          parsed("VTR-{YYYY}-{NNNN}").usesYear,
          !parsed("RL-{NNN}").usesYear,
          parsed("{OU}-{NN}").usesUnit
        )
      },
      test("problems are reported") {
        def problems(p: String) = NumberPattern.parse(p).left.getOrElse(Nil)
        assertTrue(
          problems("").exists(_.contains("required")),
          problems("POL-{YYYY}").exists(_.contains("exactly one counter")),
          problems("{NN}-{NNN}").exists(_.contains("exactly one counter")),
          problems("POL-{XY}-{NN}").exists(_.contains("unknown placeholder {XY}")),
          problems("POL-{NNNNNNNNNNN}").exists(_.contains("unknown placeholder")),
          problems("POL {NN}").exists(_.contains("not allowed")),
          problems("POL/{NN}").exists(_.contains("not allowed")),
          problems("POL-{NN").exists(_.contains("braces")),
          problems("POL-}{NN}").exists(_.contains("braces")),
          problems("X" * 101 + "{N}").exists(_.contains("at most 100"))
        )
      }
    ),
    suite("formatter")(
      test("pads the counter to the minimum digits and never cuts it") {
        val p = parsed("POL-{YYYY}-{NNNN}")
        assertTrue(
          p.format(2026, None, 7) == Right("POL-2026-0007"),
          p.format(2026, None, 12345) == Right("POL-2026-12345"),
          parsed("C{YY}.{NN}").format(2027, None, 3) == Right("C27.03"),
          parsed("RL-{NNN}").format(2026, None, 0) == Right("RL-000")
        )
      },
      test("the organizational unit is required by {OU} and made fit for a number") {
        val p = parsed("{OU}-{NNN}")
        assertTrue(
          p.format(2026, None, 1).isLeft,
          p.format(2026, Some(" & "), 1).isLeft,
          p.format(2026, Some("Legal & Compliance"), 1) == Right("Legal-Compliance-001"),
          p.format(2026, Some("Qualitätsmanagement"), 2) == Right("Qualitätsmanagement-002"),
          NumberPattern.unitSegment("  IT / Ops ") == "IT-Ops"
        )
      },
      test("results that are no valid document number are rejected") {
        assertTrue(
          parsed("-{N}").format(2026, None, 1).isLeft,
          parsed("X" * 60 + "{NNNNNNNNNN}").format(2026, None, 1).isLeft
        )
      }
    ),
    suite("document numbers")(
      test("letters, digits, '.', '_' and '-', at most 64, starting with a letter or digit") {
        assertTrue(
          DocumentNumber.validate("VTR-2026-0001") == Right("VTR-2026-0001"),
          DocumentNumber.validate("Ä.1_b") == Right("Ä.1_b"),
          DocumentNumber.validate("").isLeft,
          DocumentNumber.validate("-1").isLeft,
          DocumentNumber.validate("A/1").isLeft,
          DocumentNumber.validate("A 1").isLeft,
          DocumentNumber.validate("A" * 65).isLeft
        )
      }
    ),
    suite("ranges")(
      test("the counter period is the year with a yearly reset, otherwise one period") {
        assertTrue(
          range("P-{YYYY}-{NN}").period(2027) == 2027,
          range("P-{NN}", yearly = false).period(2027) == 0
        )
      },
      test("the next counter starts at the start value and is raised by a higher one, never lowered") {
        val r = range("P-{NN}", start = 10, yearly = false)
        assertTrue(
          r.nextCounter(None) == 10,
          r.nextCounter(Some(3)) == 10,
          r.nextCounter(Some(42)) == 42
        )
      },
      test("settings are checked") {
        def check(key: String, pattern: String, start: Long = 1, yearly: Boolean = false) =
          NumberRange.check(key, "Name", NumberPattern.parse(pattern), start, yearly)
        assertTrue(
          check("contracts", "VTR-{YYYY}-{NNNN}", yearly = true).isEmpty,
          check("Contracts", "VTR-{NNNN}").exists(_.contains("key")),
          check("contracts", "VTR-{NNNN}", yearly = true).exists(_.contains("yearly reset")),
          check("contracts", "VTR-{NNNN}", start = -1).exists(_.contains("start value")),
          check("contracts", "VTR-{X}").nonEmpty,
          NumberRange.check("c", " ", NumberPattern.parse("A{N}"), 1, false).exists(_.contains("name"))
        )
      }
    )
  )
