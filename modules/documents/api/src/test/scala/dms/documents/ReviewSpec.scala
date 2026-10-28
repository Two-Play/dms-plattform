package dms.documents

import zio.test.*

import java.time.LocalDate

object ReviewSpec extends ZIOSpecDefault:
  private val validFrom = LocalDate.of(2026, 1, 1)
  private val yearly    = ReviewPolicy(Some(12), 60)

  def spec = suite("Review")(
    test("the review date is the valid-from date plus the class's interval") {
      assertTrue(
        Review.dueOn(validFrom, None, yearly) == Some(LocalDate.of(2027, 1, 1)),
        Review.dueOn(LocalDate.of(2026, 1, 31), None, ReviewPolicy(Some(1))) == Some(
          LocalDate.of(2026, 2, 28)
        )
      )
    },
    test(
      "the document's next review date can only shorten the cycle and must lie after the valid-from date"
    ) {
      assertTrue(
        Review.dueOn(validFrom, Some(LocalDate.of(2026, 6, 1)), yearly) == Some(LocalDate.of(2026, 6, 1)),
        Review.dueOn(validFrom, Some(LocalDate.of(2028, 1, 1)), yearly) == Some(LocalDate.of(2027, 1, 1)),
        // A date copied from the previous version is stale and ignored.
        Review.dueOn(validFrom, Some(LocalDate.of(2025, 6, 1)), yearly) == Some(LocalDate.of(2027, 1, 1)),
        Review.dueOn(validFrom, Some(validFrom), ReviewPolicy()).isEmpty
      )
    },
    test("without interval only the document's date counts; without both there is no periodic review") {
      assertTrue(
        Review.dueOn(validFrom, Some(LocalDate.of(2026, 9, 1)), ReviewPolicy()) == Some(
          LocalDate.of(2026, 9, 1)
        ),
        Review.dueOn(validFrom, None, ReviewPolicy()).isEmpty
      )
    },
    test("the task is due from the lead time before the review date on") {
      val due = LocalDate.of(2027, 1, 1)
      assertTrue(
        Review.opensOn(due, yearly) == LocalDate.of(2026, 11, 2),
        !Review.taskDue(due, yearly, LocalDate.of(2026, 11, 1)),
        Review.taskDue(due, yearly, LocalDate.of(2026, 11, 2)),
        Review.taskDue(due, yearly, LocalDate.of(2027, 2, 1)),
        Review.opensOn(due, ReviewPolicy()) == LocalDate.of(2026, 11, 2),
        Review.opensOn(due, ReviewPolicy(leadDays = 0)) == due
      )
    },
    test("recertification sets the chosen date or today plus the interval, always after today") {
      val today = LocalDate.of(2027, 1, 5)
      assertTrue(
        Review.recertifiedDueOn(None, yearly, today) == Right(LocalDate.of(2028, 1, 5)),
        Review.recertifiedDueOn(Some(LocalDate.of(2027, 7, 1)), yearly, today) == Right(
          LocalDate.of(2027, 7, 1)
        ),
        Review.recertifiedDueOn(Some(today), yearly, today).isLeft,
        Review.recertifiedDueOn(None, ReviewPolicy(), today).isLeft,
        Review.recertifiedDueOn(Some(LocalDate.of(2027, 3, 1)), ReviewPolicy(), today) == Right(
          LocalDate.of(2027, 3, 1)
        )
      )
    },
    test("review settings are limited to sensible ranges") {
      assertTrue(
        ReviewPolicy.default == ReviewPolicy(None, 60),
        yearly.problems.isEmpty,
        ReviewPolicy(Some(0), 60).problems.size == 1,
        ReviewPolicy(Some(121), 60).problems.size == 1,
        ReviewPolicy(None, -1).problems.size == 1,
        ReviewPolicy(None, 366).problems.size == 1,
        ReviewPolicy(Some(120), 365).problems.isEmpty
      )
    }
  )
