package dms.app

import dms.documents.metadata.MetadataValue
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/** Filtering, facets, sorting and markers of the library (DMS-119) without database. */
object LibrarySpec extends ZIOSpecDefault:

  private val zone = ZoneId.of("Europe/Berlin")
  private val at   = Instant.parse("2026-10-01T10:00:00Z")

  private def item(title: String, number: Option[String] = None, unit: Option[String] = None) =
    LibraryItem(
      UUID.randomUUID(),
      UUID.randomUUID(),
      title,
      number,
      "1.0",
      "Published",
      List("instruction"),
      "Central instruction",
      unit,
      "Internal",
      Nil,
      "",
      None,
      None,
      at,
      at,
      isNew = false,
      changed = false,
      requiresAcknowledgement = false,
      acknowledgementPending = false
    )

  def spec = suite("Library")(
    test("document numbers sort naturally; documents without number come last") {
      val items =
        List(item("C", Some("RL-10")), item("A"), item("B", Some("RL-9")), item("D", Some("rl-100")))
      val sorted = Library.sort(items, LibrarySort.Number, ascending = true, zone).map(_.title)
      val desc   = Library.sort(items, LibrarySort.Number, ascending = false, zone).map(_.title)
      assertTrue(sorted == List("B", "C", "D", "A"), desc == List("D", "C", "B", "A"))
    },
    test("titles sort in German collation: umlauts next to their base letter, case ignored") {
      val items = List(item("Zulage"), item("Überstunden"), item("arbeitszeit"), item("Urlaub"))
      assertTrue(
        Library.sort(items, LibrarySort.Title, ascending = true, zone).map(_.title) ==
          List("arbeitszeit", "Überstunden", "Urlaub", "Zulage")
      )
    },
    test("the description is the summary field, on one line and cut at a word boundary") {
      val long = ("word " * 100).trim
      val cut  = Library.summary(Map(Library.SummaryField -> MetadataValue.Text(long)))
      assertTrue(
        Library.summary(Map(Library.SummaryField -> MetadataValue.Text("  Two\n lines  "))) == Some(
          "Two lines"
        ),
        Library.summary(Map(Library.SummaryField -> MetadataValue.Text("  "))).isEmpty,
        Library.summary(Map.empty).isEmpty,
        cut.exists(_.length <= Library.MaxSummaryLength + 1),
        cut.exists(_.endsWith("word…"))
      )
    },
    test("new and changed are decided against 00:00 of the reference date in the tenant's time zone") {
      val boundary = Library.changeBoundary(LocalDate.of(2026, 10, 1), zone)
      val before   = boundary.minusSeconds(1)
      assertTrue(
        boundary == Instant.parse("2026-09-30T22:00:00Z"),
        Library.isNew(boundary, boundary),
        !Library.isNew(before, boundary),
        Library.isChanged(before, boundary, boundary),
        !Library.isChanged(before, before, boundary),
        !Library.isChanged(boundary, boundary, boundary)
      )
    },
    test("tags come from the core field, trimmed and without duplicates") {
      val values = Map(
        "tags" -> MetadataValue.Many(
          List(MetadataValue.Text(" travel "), MetadataValue.Text("travel"), MetadataValue.Text(""))
        )
      )
      assertTrue(Library.tags(values) == List("travel"))
    },
    test("an item without unit matches no unit filter and is not counted in the unit facet") {
      val items  = List(item("A", unit = Some("HR")), item("B"))
      val filter = LibraryFilter(units = Set("HR"))
      assertTrue(
        Library.filter(items, filter).map(_.title) == List("A"),
        Library.facets(items, LibraryFilter(), Map.empty).units.map(f => (f.value, f.count)) == List(
          ("HR", 1)
        )
      )
    }
  )
