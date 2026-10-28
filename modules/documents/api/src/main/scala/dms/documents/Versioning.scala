package dms.documents

import dms.documents.metadata.MetadataValue
import dms.kernel.VersionNo

/** Kind of change a new version makes (business concept 5.2). */
enum ChangeKind:
  /** Change of content: full approval workflow, the major number increases (1.0 → 2.0). */
  case Major

  /** Editorial change without effect on the content (typo, layout, reference): the minor number increases
    * (1.0 → 1.1). Needs a reason, which the owner confirms.
    */
  case Minor

/** Pure rules of the version model (business concept 5.2): version numbers, working states, which statuses
  * are open, valid or frozen, and the validity scope.
  */
object Versioning:
  import DocumentStatus.*

  /** Statuses of a version that is being worked on. A document has at most one open version. */
  val open: Set[DocumentStatus] = Set(Draft, InReview, InApproval)

  /** Statuses of the valid version. A document has at most one valid version per scope. */
  val valid: Set[DocumentStatus] = Set(Published, ReviewDue)

  /** Statuses in which a version is frozen: content, field values, metadata snapshot, template version,
    * rendition and checksum never change again.
    */
  val frozen: Set[DocumentStatus] = DocumentStatus.values.toSet -- open

  /** Only drafts can be changed; in review and approval a version can only be commented on. */
  def isEditable(status: DocumentStatus): Boolean = status == Draft

  def isOpen(status: DocumentStatus): Boolean  = open.contains(status)
  def isValid(status: DocumentStatus): Boolean = valid.contains(status)

  /** The number of the first version of a document. */
  val first: VersionNo = VersionNo.Initial

  /** The number of the next version.
    *
    * @param latestReleased
    *   the highest number that was ever approved; `None` if no version was approved yet (the first version is
    *   still being worked on, or it was withdrawn)
    */
  def next(latestReleased: Option[VersionNo], kind: ChangeKind): VersionNo =
    latestReleased.fold(first) { v =>
      kind match
        case ChangeKind.Major => v.nextMajor
        case ChangeKind.Minor => v.nextMinor
    }

  /** The working state of a new version: working states of a version number are counted continuously, so a
    * new draft for a number whose earlier draft was withdrawn continues after its last working state.
    */
  def nextWorkingState(previous: Iterable[Int]): Int = previous.maxOption.fold(1)(_ + 1)

  /** The label of a version: `1.1-draft.3` while it is open, `1.1` once it is approved. */
  def label(number: VersionNo, status: DocumentStatus, workingState: Int): String =
    if isOpen(status) then s"${number.show}-draft.$workingState" else number.show

  private val Label = """(\d+)\.(\d+)(?:-draft\.(\d+))?""".r

  /** Parses a label produced by [[label]] into the number and, for open versions, the working state. */
  def parseLabel(label: String): Option[(VersionNo, Option[Int])] = label match
    case Label(major, minor, working) =>
      val state: Option[Option[Int]] = Option(working) match
        case None    => Some(None)
        case Some(w) => w.toIntOption.filter(_ >= 1).map(Some(_))
      for
        number <- VersionNo.parse(s"$major.$minor")
        s      <- state
      yield (number, s)
    case _ => None

  /** The key of the scope field used for the validity scope (core field `scope`). */
  val ScopeField = "scope"

  /** The validity scope of a version, derived from its `scope` field: the distinct, trimmed values in sorted
    * order joined by `|`; empty if the field is not set (the document applies without restriction). Two
    * versions are valid at the same time only with different scopes (business concept 5.2).
    */
  def scopeKey(values: Map[String, MetadataValue]): String =
    def texts(value: MetadataValue): List[String] = value match
      case MetadataValue.Text(t)      => List(t)
      case MetadataValue.Selection(c) => List(c)
      case MetadataValue.Many(vs)     => vs.flatMap(texts)
      case _                          => Nil
    values.get(ScopeField).toList.flatMap(texts).map(_.trim).filter(_.nonEmpty).distinct.sorted.mkString("|")
