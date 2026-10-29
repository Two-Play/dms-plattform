package dms.app

import dms.documents.metadata.MetadataValue

import java.text.Collator
import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Locale, UUID}
import scala.math.Ordering.Implicits.*

/** A document version of the library (DMS-119): a version valid now that the caller may read. */
final case class LibraryItem(
    documentId: UUID,
    versionId: UUID,
    title: String,
    documentNumber: Option[String],
    /** Version number (index), e.g. `2.1`. */
    version: String,
    /** Published, or ReviewDue (still valid while its periodic review is due). */
    status: String,
    classPath: List[String],
    /** Label of the document's class (the last entry of `classPath`). */
    classLabel: String,
    /** The issuing unit: the version's organizational unit (ADR 0039). */
    organizationalUnit: Option[String],
    /** Public, Internal, Confidential or StrictlyConfidential. */
    confidentiality: String,
    tags: List[String],
    /** Validity scope of the version; empty: unrestricted. */
    scope: String,
    /** Short description: the version's `summary` field, if its class has one. */
    summary: Option[String],
    validFrom: Option[LocalDate],
    /** When the version became valid: 00:00 of its valid-from date in the tenant's time zone, or its
      * publication if that was later.
      */
    validSince: Instant,
    /** When the version was published. */
    publishedAt: Instant,
    /** The document's first version became valid on or after `since`. */
    isNew: Boolean,
    /** The document existed before `since` and this version became valid on or after it. */
    changed: Boolean,
    /** Whether the version asks its readers for an acknowledgement (ADR 0027). */
    requiresAcknowledgement: Boolean,
    /** The caller is asked to acknowledge the version and has not done so yet. */
    acknowledgementPending: Boolean
)

/** Sort orders of the library. */
enum LibrarySort:
  case Title, Number, ValidFrom, LastChanged

  /** Default direction: titles and numbers ascending, dates newest first. */
  def defaultAscending: Boolean = this match
    case Title | Number          => true
    case ValidFrom | LastChanged => false

object LibrarySort:
  def parse(value: String): Option[LibrarySort] =
    values.find(_.toString.equalsIgnoreCase(value.trim))

/** The filters of the library; every non-empty set must match (values within a set are alternatives). */
final case class LibraryFilter(
    text: Option[String] = None,
    classes: Set[String] = Set.empty,
    units: Set[String] = Set.empty,
    tags: Set[String] = Set.empty,
    confidentiality: Set[String] = Set.empty,
    /** Only versions that are new or changed since the reference date. */
    changedOnly: Boolean = false,
    /** Only versions the caller has yet to acknowledge. */
    pendingOnly: Boolean = false
)

/** Count of a facet value, with a display label where the API knows one (class labels). */
final case class LibraryFacet(value: String, label: Option[String], count: Int)

final case class LibraryFacets(
    classes: List[LibraryFacet],
    units: List[LibraryFacet],
    tags: List[LibraryFacet],
    confidentiality: List[LibraryFacet],
    /** Versions new or changed since the reference date. */
    changed: Int,
    /** Versions the caller has yet to acknowledge. */
    pending: Int
)

/** Filtering, facets and sorting of the library (DMS-119, ADR 0043), over the versions the caller may read.
  *
  * Facets are disjunctive: the counts of a facet apply every filter except its own, so the other values of
  * the same facet stay selectable.
  */
object Library:

  /** Key of the optional tenant field that holds a document's short description. */
  val SummaryField = "summary"

  /** Longest description shown in the library; longer ones are cut at a word boundary. */
  val MaxSummaryLength = 300

  /** Default reference date for "new" and "changed": this many days before today. */
  val DefaultChangeWindowDays = 30L

  private val TagsField = "tags"

  /** The title of a version as approved (field `title`); the document's title may have changed since. */
  def title(values: Map[String, MetadataValue]): Option[String] =
    values.get("title").collect { case MetadataValue.Text(t) if t.trim.nonEmpty => t.trim }

  /** The tags of a version (core field `tags`), trimmed, without duplicates. */
  def tags(values: Map[String, MetadataValue]): List[String] =
    def texts(value: MetadataValue): List[String] = value match
      case MetadataValue.Text(t)      => List(t)
      case MetadataValue.Selection(c) => List(c)
      case MetadataValue.Many(vs)     => vs.flatMap(texts)
      case _                          => Nil
    values.get(TagsField).toList.flatMap(texts).map(_.trim).filter(_.nonEmpty).distinct

  /** The short description of a version: its `summary` field, shortened to [[MaxSummaryLength]]. */
  def summary(values: Map[String, MetadataValue]): Option[String] =
    values
      .get(SummaryField)
      .collect { case MetadataValue.Text(t) => t.trim }
      .filter(_.nonEmpty)
      .map(shorten)

  private def shorten(text: String): String =
    val single = text.replaceAll("\\s+", " ")
    if single.length <= MaxSummaryLength then single
    else
      val cut  = single.take(MaxSummaryLength)
      val word = cut.lastIndexOf(' ')
      (if word > MaxSummaryLength / 2 then cut.take(word) else cut).stripTrailing() + "…"

  /** The start of `since` in the tenant's time zone: versions valid from then on count as new or changed. */
  def changeBoundary(since: LocalDate, zone: ZoneId): Instant = since.atStartOfDay(zone).toInstant

  /** Whether the document is new since `boundary`: its first version became valid then or later. */
  def isNew(firstPublishedAt: Instant, boundary: Instant): Boolean = !firstPublishedAt.isBefore(boundary)

  /** Whether the version changed the document since `boundary`: the document is not new and this version
    * became valid then or later.
    */
  def isChanged(firstPublishedAt: Instant, validSince: Instant, boundary: Instant): Boolean =
    !isNew(firstPublishedAt, boundary) && !validSince.isBefore(boundary)

  private def lower(s: String): String = s.toLowerCase(Locale.ROOT)

  /** Every word of `text` occurs in title, number, description, class, unit, tags or scope. */
  private def matchesText(item: LibraryItem, text: Option[String]): Boolean =
    val words = text.toList.flatMap(t => lower(t).split("\\s+").toList).filter(_.nonEmpty)
    words.isEmpty || {
      val haystack = lower(
        (List(item.title, item.classLabel, item.scope) ++ item.documentNumber ++ item.summary ++
          item.organizationalUnit ++ item.tags ++ item.classPath).mkString("\n")
      )
      words.forall(haystack.contains)
    }

  private enum Dimension:
    case Class, Unit, Tag, Confidentiality, Changed, Pending

  private def matches(item: LibraryItem, f: LibraryFilter, except: Option[Dimension]): Boolean =
    def unless(d: Dimension)(condition: => Boolean) = except.contains(d) || condition
    matchesText(item, f.text) &&
    unless(Dimension.Class)(f.classes.isEmpty || item.classPath.exists(f.classes.contains)) &&
    unless(Dimension.Unit)(f.units.isEmpty || item.organizationalUnit.exists(f.units.contains)) &&
    unless(Dimension.Tag)(f.tags.isEmpty || item.tags.exists(f.tags.contains)) &&
    unless(Dimension.Confidentiality)(
      f.confidentiality.isEmpty || f.confidentiality.contains(item.confidentiality)
    ) &&
    unless(Dimension.Changed)(!f.changedOnly || item.isNew || item.changed) &&
    unless(Dimension.Pending)(!f.pendingOnly || item.acknowledgementPending)

  /** The items that match every filter. */
  def filter(items: List[LibraryItem], f: LibraryFilter): List[LibraryItem] =
    items.filter(matches(_, f, None))

  /** Facet counts over `items` (disjunctive, see above); values by count, then alphabetically. */
  def facets(items: List[LibraryItem], f: LibraryFilter, classLabels: Map[String, String]): LibraryFacets =
    def counted(d: Dimension)(values: LibraryItem => List[String]): List[(String, Int)] =
      items
        .filter(matches(_, f, Some(d)))
        .flatMap(i => values(i).distinct)
        .groupMapReduce(identity)(_ => 1)(_ + _)
        .toList
        .sortBy((value, count) => (-count, lower(value), value))
    def plain(counts: List[(String, Int)]) = counts.map((v, c) => LibraryFacet(v, None, c))
    LibraryFacets(
      counted(Dimension.Class)(_.classPath.lastOption.toList).map((v, c) =>
        LibraryFacet(v, classLabels.get(v), c)
      ),
      plain(counted(Dimension.Unit)(_.organizationalUnit.toList)),
      plain(counted(Dimension.Tag)(_.tags)),
      plain(counted(Dimension.Confidentiality)(i => List(i.confidentiality))),
      items.count(i => matches(i, f, Some(Dimension.Changed)) && (i.isNew || i.changed)),
      items.count(i => matches(i, f, Some(Dimension.Pending)) && i.acknowledgementPending)
    )

  /** German collation (the tenants' default language): umlauts next to their base letter, case ignored. */
  private def collator: Collator =
    val c = Collator.getInstance(Locale.GERMAN)
    c.setStrength(Collator.SECONDARY)
    c

  /** Sorted by `sort`; ties (and items without a number when sorting by number, which come last) by title. */
  def sort(items: List[LibraryItem], sort: LibrarySort, ascending: Boolean, zone: ZoneId): List[LibraryItem] =
    val titles                         = collator
    val byTitle: Ordering[LibraryItem] =
      Ordering
        .fromLessThan[LibraryItem]((a, b) => titles.compare(a.title, b.title) < 0)
        .orElseBy(_.documentId.toString)
        .orElseBy(_.scope)
    def directed[K: Ordering](key: LibraryItem => K): Ordering[LibraryItem] =
      val primary = Ordering.by(key)
      (if ascending then primary else primary.reverse).orElse(byTitle)
    sort match
      case LibrarySort.Title =>
        items.sorted(using if ascending then byTitle else byTitle.reverse)
      case LibrarySort.Number =>
        val (numbered, unnumbered) = items.partition(_.documentNumber.isDefined)
        numbered.sorted(using directed(i => numberKey(i.documentNumber.getOrElse("")))) ++
          unnumbered.sorted(using byTitle)
      case LibrarySort.ValidFrom =>
        items.sorted(using
          directed(i => i.validFrom.getOrElse(LocalDate.ofInstant(i.validSince, zone)).toEpochDay)
        )
      case LibrarySort.LastChanged =>
        items.sorted(using directed(_.publishedAt))

  /** Natural order of document numbers: digit runs compare by value, so `RL-9` comes before `RL-10`. */
  private def numberKey(number: String): List[(Int, BigInt, String)] =
    "\\d+|\\D+".r
      .findAllIn(lower(number))
      .map(part => if part.head.isDigit then (0, BigInt(part), "") else (1, BigInt(0), part))
      .toList

  /** One page (1-based) of `items`. */
  def page[A](items: List[A], page: Int, size: Int): List[A] = items.slice((page - 1) * size, page * size)
