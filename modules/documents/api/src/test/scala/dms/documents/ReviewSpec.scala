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
    },
    test("a review date moves with the interval and never before today") {
      val today = LocalDate.of(2026, 3, 1)
      val state = ReviewDateState(Some(LocalDate.of(2027, 1, 1)), validFrom)
      assertTrue(
        Review.recalculated(state, yearly, today) == ReviewDateChange.Unchanged,
        Review.recalculated(state, ReviewPolicy(Some(24)), today) ==
          ReviewDateChange.Moved(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 1)),
        Review.recalculated(state, ReviewPolicy(Some(1)), today) ==
          ReviewDateChange.Moved(today, LocalDate.of(2026, 2, 1))
      )
    },
    test("the document's next review date still shortens the cycle when the interval changes") {
      val date  = LocalDate.of(2026, 6, 1)
      val state = ReviewDateState(Some(date), validFrom, Some(date))
      assertTrue(
        Review.recalculated(state, ReviewPolicy(Some(24)), validFrom) == ReviewDateChange.Unchanged
      )
    },
    test("a version without a date gets one, and without an interval stays without") {
      val state = ReviewDateState(None, validFrom)
      val today = LocalDate.of(2028, 3, 1)
      assertTrue(
        Review.recalculated(state, yearly, validFrom) ==
          ReviewDateChange.Moved(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1)),
        Review.recalculated(state, yearly, today) ==
          ReviewDateChange.Moved(today, LocalDate.of(2027, 1, 1)),
        Review.recalculated(state, ReviewPolicy(), today) == ReviewDateChange.Unchanged
      )
    },
    test("a reached review date stays in both directions, also on the day itself") {
      val due   = LocalDate.of(2027, 1, 1)
      val state = ReviewDateState(Some(due), validFrom, openTask = true)
      val later = ReviewPolicy(Some(24))
      assertTrue(
        Review.recalculated(state, later, due) ==
          ReviewDateChange.Kept(ReviewDateKept.Overdue, Some(LocalDate.of(2028, 1, 1))),
        Review.recalculated(state, later, due.plusDays(40)) ==
          ReviewDateChange.Kept(ReviewDateKept.Overdue, Some(LocalDate.of(2028, 1, 1))),
        Review.recalculated(state, ReviewPolicy(Some(6)), due) ==
          ReviewDateChange.Kept(ReviewDateKept.Overdue, Some(LocalDate.of(2026, 7, 1)))
      )
    },
    test("a date a task was decided for stays") {
      val state = ReviewDateState(Some(LocalDate.of(2027, 1, 1)), validFrom, decided = true)
      assertTrue(
        Review.recalculated(state, ReviewPolicy(Some(6)), validFrom) ==
          ReviewDateChange.Kept(ReviewDateKept.Decided, Some(LocalDate.of(2026, 7, 1)))
      )
    },
    test("a recertified date counts from the recertification and only moves earlier") {
      val day   = LocalDate.of(2026, 11, 10)
      val state = ReviewDateState(Some(LocalDate.of(2027, 6, 1)), validFrom, recertifiedOn = Some(day))
      assertTrue(
        Review.recalculated(state, ReviewPolicy(Some(24)), day) ==
          ReviewDateChange.Kept(ReviewDateKept.Recertified, Some(LocalDate.of(2028, 11, 10))),
        Review.recalculated(state, ReviewPolicy(Some(3)), day) ==
          ReviewDateChange.Moved(LocalDate.of(2027, 2, 10), LocalDate.of(2027, 2, 10))
      )
    },
    test("with an open task a date only moves earlier") {
      val state = ReviewDateState(Some(LocalDate.of(2027, 1, 1)), validFrom, openTask = true)
      val today = LocalDate.of(2026, 11, 10)
      assertTrue(
        Review.recalculated(state, ReviewPolicy(Some(24)), today) ==
          ReviewDateChange.Kept(ReviewDateKept.OpenTask, Some(LocalDate.of(2028, 1, 1))),
        Review.recalculated(state, ReviewPolicy(Some(11)), today) ==
          ReviewDateChange.Moved(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))
      )
    },
    test("removing the interval keeps the date") {
      val state = ReviewDateState(Some(LocalDate.of(2027, 1, 1)), validFrom)
      assertTrue(
        Review.recalculated(state, ReviewPolicy(), validFrom) ==
          ReviewDateChange.Kept(ReviewDateKept.NoInterval, None)
      )
    },
    test("inherited settings have no values of their own") {
      assertTrue(
        ReviewPolicy.inherit.problems.isEmpty,
        ReviewPolicy(Some(12), inherited = true).problems.nonEmpty,
        ReviewPolicy(None, 30, inherited = true).problems.nonEmpty
      )
    }
  )
