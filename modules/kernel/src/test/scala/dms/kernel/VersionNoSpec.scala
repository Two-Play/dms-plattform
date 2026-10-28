package dms.kernel

import zio.test.*

object VersionNoSpec extends ZIOSpecDefault:
  def spec = suite("VersionNo")(
    test("major and minor versions increment correctly") {
      assertTrue(
        VersionNo.Initial.nextMinor.show == "1.1",
        VersionNo(1, 3).nextMajor.show == "2.0"
      )
    },
    test("parsing accepts valid and rejects invalid input") {
      assertTrue(
        VersionNo.parse("2.4") == Some(VersionNo(2, 4)),
        VersionNo.parse("2").isEmpty,
        VersionNo.parse("a.b").isEmpty,
        VersionNo.parse("-1.0").isEmpty
      )
    },
    test("ordering compares major first, then minor") {
      val sorted = List(VersionNo(2, 0), VersionNo(1, 10), VersionNo(1, 2)).sorted
      assertTrue(sorted == List(VersionNo(1, 2), VersionNo(1, 10), VersionNo(2, 0)))
    }
  )
