package dms.templates

import dms.kernel.{TemplateId, UserId}

import java.time.Instant
import java.util.UUID

/** Lifecycle of a template version (business concept 4.1): Draft → Testing → Released → Deprecated →
  * Archived. Only released versions are offered in the template catalog.
  */
enum TemplateStatus:
  case Draft, Testing, Released, Deprecated, Archived

/** One version of a template. A version is immutable once released; changes create a new version.
  *
  * @param documentClassId
  *   the document class whose metadata profile provides the fields the placeholders refer to
  * @param documentClassVersion
  *   the class version the template was designed against
  * @param editedBy
  *   users other than the creator who changed the draft, in order of their first change; together with the
  *   creator they cannot release the version (segregation of duties)
  */
final case class TemplateVersion(
    templateId: TemplateId,
    version: Int,
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    status: TemplateStatus,
    content: TemplateContent,
    createdBy: UserId,
    createdAt: Instant,
    releasedBy: Option[UserId] = None,
    releasedAt: Option[Instant] = None,
    editedBy: List[UserId] = Nil
):
  /** Everyone who created or changed this version. */
  def contributors: Set[UserId] = editedBy.toSet + createdBy

enum TemplateLifecycleError:
  case TransitionNotAllowed(from: TemplateStatus, to: TemplateStatus)

  /** Only drafts can be changed. */
  case NotEditable(status: TemplateStatus)

  /** The user created or changed the version and therefore cannot release it. */
  case SegregationOfDutiesViolated(user: UserId)

  def message: String = this match
    case TransitionNotAllowed(from, to) => s"a template version cannot change from $from to $to"
    case NotEditable(status)            => s"only drafts can be changed, this version is $status"
    case SegregationOfDutiesViolated(_) =>
      "segregation of duties: a template designer cannot release a version they created or changed"

/** Pure state machine of the template lifecycle (business concept 4.1). Releasing a version does not
  * deprecate older released versions; deprecating is an explicit step.
  */
object TemplateLifecycle:
  import TemplateStatus.*

  private val transitions: Set[(TemplateStatus, TemplateStatus)] = Set(
    Draft      -> Testing,
    Testing    -> Draft,
    Testing    -> Released,
    Released   -> Deprecated,
    Deprecated -> Archived
  )

  def allowedTargets(from: TemplateStatus): Set[TemplateStatus] = transitions.collect { case (`from`, to) =>
    to
  }

  def isEditable(status: TemplateStatus): Boolean = status == Draft

  /** A version that is being worked on; a template has at most one at a time. */
  def isOpen(status: TemplateStatus): Boolean = status == Draft || status == Testing

  /** Checks a status change of `version` by `actor`; releasing requires a person who neither created nor
    * changed the version (four-eyes principle).
    */
  def transition(
      version: TemplateVersion,
      to: TemplateStatus,
      actor: UserId
  ): Either[TemplateLifecycleError, TemplateStatus] =
    if !transitions.contains(version.status -> to) then
      Left(TemplateLifecycleError.TransitionNotAllowed(version.status, to))
    else if to == Released && version.contributors.contains(actor) then
      Left(TemplateLifecycleError.SegregationOfDutiesViolated(actor))
    else Right(to)
