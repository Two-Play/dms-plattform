package dms.notifications

import dms.db.queue.OutboxEvent
import dms.kernel.{DocumentId, TenantId, UserId}
import zio.*

import java.time.Instant
import java.util.UUID

/** Groups of notifications a user can switch email delivery on or off for (DMS-130, ADR 0052); in-app
  * notifications are always delivered.
  *
  * @param key
  *   stable identifier in the API and in `dms.notification_settings.email`
  * @param emailByDefault
  *   whether email is sent while the user has not chosen
  */
enum NotificationCategory(val key: String, val emailByDefault: Boolean):
  /** Review and approval tasks assigned, returned, approved; participants missing. */
  case Workflow extends NotificationCategory("workflow", true)

  /** Deadlines of review and approval tasks: reminders, overdue tasks, escalations (DMS-87). */
  case Deadline extends NotificationCategory("deadline", true)

  /** Acknowledgements required and reminders of open ones. */
  case Acknowledgement extends NotificationCategory("acknowledgement", true)

  /** Deputyships set, begun, ended. */
  case Deputyship extends NotificationCategory("deputyship", true)

  /** Documents the user owns became valid. */
  case Publication extends NotificationCategory("publication", true)

  /** Legal holds set or released (records managers). */
  case LegalHold extends NotificationCategory("legal-hold", false)

object NotificationCategory:
  def fromKey(key: String): Option[NotificationCategory] = values.find(_.key == key)

/** How a notification reaches the user. */
enum Channel(val key: String):
  case InApp extends Channel("in-app")
  case Email extends Channel("email")

object Channel:
  def fromKey(key: String): Option[Channel] = values.find(_.key == key)

/** Language of notifications; the user's choice, else the tenant's. */
enum Language(val code: String):
  case De extends Language("de")
  case En extends Language("en")

object Language:
  def fromCode(code: String): Option[Language] = values.find(_.code == code.trim.toLowerCase)

/** The document a notification is about. Without `versionId` the read check accepts any version. */
final case class DocumentRef(documentId: DocumentId, versionId: Option[UUID])

/** What an outbox event means for whom: one notification of `kind` per recipient.
  *
  * @param kind
  *   the template, e.g. `workflow.task-assigned` (see `NotificationTemplates` in `notifications-core`)
  * @param params
  *   values the template uses (step name, version label, round, …); never the document title, which is looked
  *   up at delivery time after the read check
  */
final case class Notice(
    kind: String,
    category: NotificationCategory,
    recipients: List[UserId],
    document: Option[DocumentRef] = None,
    params: Map[String, String] = Map.empty
)

/** Turns outbox events into notices; implemented by the application. */
trait NotificationEvents:
  /** Cheap pre-check without database access: whether `event` can lead to notifications at all. */
  def concerns(event: OutboxEvent): Boolean

  /** The notices of `event`, possibly none. Runs inside `Db.transact(event.tenant)`. */
  def describe(event: OutboxEvent): IO[String, List[Notice]]

/** What a recipient may learn about the document of a notification. */
final case class DocumentFacts(title: String, versionLabel: Option[String], number: Option[String])

/** The read check at delivery time; implemented by the application with the authorization policy. */
trait NotificationDocuments:

  /** The facts of the document if `user` may read it (the version, if given) now; `None` if not, or if it
    * does not exist (any more). Runs inside `Db.transact(tenant)`.
    */
  def visibleTo(tenant: TenantId, user: UserId, document: DocumentRef): IO[String, Option[DocumentFacts]]

/** A person as the user directory knows them (ADR 0037). */
final case class Person(displayName: Option[String], email: Option[String])

/** Names and email addresses of recipients; implemented by the application with the user directory. Called
  * outside database transactions.
  */
trait RecipientDirectory:

  /** The people of `tenant` among `users`; unknown users are missing. Fails if the directory is unreachable.
    */
  def people(tenant: TenantId, users: Set[UserId]): IO[String, Map[UserId, Person]]

object RecipientDirectory:
  /** No directory: nobody has an email address, names stay ids. */
  val none: RecipientDirectory = new RecipientDirectory:
    def people(tenant: TenantId, users: Set[UserId]): IO[String, Map[UserId, Person]] = ZIO.succeed(Map.empty)

/** An email to one recipient. `messageId` is the same on every attempt, so mail clients can drop duplicates
  * of an at-least-once delivery.
  */
final case class Mail(to: String, subject: String, text: String, messageId: String, language: Language)

/** Sends emails (SMTP in production). */
trait MailSender:
  def send(mail: Mail): IO[String, Unit]

/** An in-app notification of the caller. */
final case class Notification(
    id: UUID,
    kind: String,
    category: NotificationCategory,
    subject: String,
    body: String,
    link: Option[String],
    documentId: Option[DocumentId],
    createdAt: Instant,
    readAt: Option[Instant]
)

/** A page of notifications, newest first, with the totals of the caller. */
final case class NotificationPage(items: List[Notification], total: Int, unread: Int)

/** The caller's choices: language (`None`: the tenant's) and email per category (all categories, with the
  * defaults filled in).
  */
final case class NotificationPreferences(
    language: Option[Language],
    email: Map[NotificationCategory, Boolean],
    tenantLanguage: Language
):
  def emailFor(category: NotificationCategory): Boolean = email.getOrElse(category, category.emailByDefault)
  def effectiveLanguage: Language                       = language.getOrElse(tenantLanguage)
