package dms.documents

import dms.kernel.{DocumentId, UserId}

import java.time.Instant
import java.util.UUID

/** A reader's confirmation that they read a valid version which requires acknowledgement (business concept
  * 6.3, US-06.2, ADR 0027). Recorded once per reader and version and never changed.
  *
  * @param versionLabel
  *   the version number the reader acknowledged, e.g. `2.0`
  * @param openedAt
  *   when the reader opened the version first; acknowledging is only possible afterwards
  */
final case class Acknowledgement(
    id: UUID,
    documentId: DocumentId,
    versionId: UUID,
    versionLabel: String,
    userId: UserId,
    openedAt: Instant,
    acknowledgedAt: Instant
)

object Acknowledgements:

  /** Whether `version` asks its readers for an acknowledgement now: it is valid (Published or ReviewDue) and
    * requires acknowledgement. Superseded versions no longer do; their successor asks on its own if it
    * requires acknowledgement.
    */
  def requested(version: DocumentVersion): Boolean =
    Versioning.isValid(version.status) && version.requiresAcknowledgement

/** Who of the readers of a version must acknowledge it (DMS-100, ADR 0048): readers who hold one of `roles`,
  * belong to one of the access `groups`, or hold a role in one of the organizational `units` (any of them).
  * Without any restriction every reader must acknowledge (ADR 0027). Roles are names of business roles
  * (`dms.authz.Role`); the authorization module decides membership.
  */
final case class AcknowledgementRecipients(
    roles: Set[String] = Set.empty,
    groups: Set[String] = Set.empty,
    units: Set[String] = Set.empty
):
  def everyReader: Boolean = roles.isEmpty && groups.isEmpty && units.isEmpty

object AcknowledgementRecipients:
  val everyReader: AcknowledgementRecipients = AcknowledgementRecipients()

/** Acknowledgement settings of a document class or the tenant default (DMS-100, ADR 0048).
  *
  * @param reminderDays
  *   days after publication until the first automatic reminder of open acknowledgements and between further
  *   reminders; 0: no automatic reminders; `None`: inherited (tenant default, then the installation's
  *   setting)
  */
final case class AcknowledgementSettings(
    recipients: AcknowledgementRecipients = AcknowledgementRecipients.everyReader,
    reminderDays: Option[Int] = None
):
  def problems: List[String] =
    reminderDays
      .filterNot(d => d >= 0 && d <= AcknowledgementSettings.MaxReminderDays)
      .map(d => s"the reminder interval must be 0 to ${AcknowledgementSettings.MaxReminderDays} days, not $d")
      .toList ++
      List(
        "roles"  -> recipients.roles,
        "groups" -> recipients.groups,
        "units"  -> recipients.units
      ).collect {
        case (name, values) if values.size > AcknowledgementSettings.MaxEntries =>
          s"at most ${AcknowledgementSettings.MaxEntries} $name"
        case (name, values) if values.exists(v => v.trim.isEmpty || v.trim != v || v.length > 200) =>
          s"$name must not be blank, have surrounding blanks or exceed 200 characters"
      }

object AcknowledgementSettings:
  val MaxReminderDays: Int = 365
  val MaxEntries: Int      = 50

/** Where the acknowledgement settings of a version came from. */
enum AudienceSource:
  /** The settings of the version's class or of its nearest ancestor that has settings. */
  case Class

  /** The tenant default: no class in the path has settings. */
  case Tenant

  /** Nothing configured: every reader, the installation's reminder interval. */
  case Default

/** The acknowledgement settings that applied when a version became valid (snapshot, DMS-100, ADR 0048).
  *
  * @param sourceClass
  *   for [[AudienceSource.Class]]: the class whose settings applied
  * @param reminderDays
  *   `None`: the installation's reminder interval
  * @param recordedAt
  *   when the settings were applied; automatic reminders count their intervals from then
  */
final case class VersionAudience(
    versionId: UUID,
    source: AudienceSource,
    sourceClass: Option[UUID],
    recipients: AcknowledgementRecipients,
    reminderDays: Option[Int],
    recordedAt: Instant
)

object VersionAudience:

  /** The settings that apply to a version of a class: those of the nearest class in `chain` (the class first,
    * then its ancestors) that has settings, else the tenant default, else none. A reminder interval left open
    * (`None`) is taken from the tenant default, else from the installation.
    */
  def resolve(
      chain: List[(UUID, AcknowledgementSettings)],
      tenant: Option[AcknowledgementSettings]
  ): (AudienceSource, Option[UUID], AcknowledgementSettings) =
    chain.headOption match
      case Some((cls, s)) =>
        (
          AudienceSource.Class,
          Some(cls),
          s.copy(reminderDays = s.reminderDays.orElse(tenant.flatMap(_.reminderDays)))
        )
      case None =>
        tenant match
          case Some(s) => (AudienceSource.Tenant, None, s)
          case None    => (AudienceSource.Default, None, AcknowledgementSettings())

/** Automatic reminders of open acknowledgements (DMS-100, ADR 0048). */
object AcknowledgementReminders:

  /** Automatic reminders per reader and version at most. */
  val MaxAutomatic: Int = 3

  /** How long an owner waits between two manual reminders of the same version. */
  val ManualInterval: java.time.Duration = java.time.Duration.ofHours(24)

  /** The automatic reminder round due at `now` for a version whose settings were applied at `since`: the
    * number of whole reminder intervals since then, if at least one and at most [[MaxAutomatic]]. Only the
    * current round is sent, missed rounds are not caught up.
    */
  def dueRound(since: Instant, now: Instant, reminderDays: Int): Option[Int] =
    if reminderDays <= 0 || now.isBefore(since) then None
    else
      val round = (java.time.Duration.between(since, now).toHours / (24L * reminderDays)).toInt
      Option.when(round >= 1 && round <= MaxAutomatic)(round)
