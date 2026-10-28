package dms.audit

import dms.db.Jsonb

import java.util.UUID

/** Kind of identity that acted (business concept 7.3, "who"). */
enum ActorKind(val code: String):
  case Person    extends ActorKind("person")
  case Technical extends ActorKind("technical")

/** Channel through which the action was triggered (business concept 7.3, "through which channel"). */
enum Channel(val code: String):
  case Ui         extends Channel("ui")
  case Api        extends Channel("api")
  case Automation extends Channel("automation")

/** Who acted: a person or a technical user, optionally on behalf of someone else (deputy). */
final case class Actor(id: String, kind: ActorKind, onBehalfOf: Option[String] = None)

/** The object an action refers to ("on what"). */
final case class AuditObject(objectType: String, id: Option[UUID] = None, version: Option[String] = None)

/** One audit trail entry as provided by the caller.
  *
  * The tenant is taken from the current transaction, the timestamp from the clock at recording time.
  *
  * @param action
  *   event type, e.g. `document.created`, `document.approved`, `permission.changed`
  * @param details
  *   affected fields with before and after values or other context, as a JSON object, e.g.
  *   `{"fields":{"title":{"before":"A","after":"B"}}}`
  * @param reason
  *   justification, workflow context or triggering order ("why")
  * @param correlationId
  *   correlation id across system boundaries
  */
final case class AuditEvent(
    actor: Actor,
    action: String,
    target: AuditObject,
    channel: Channel,
    details: Jsonb = Jsonb.empty,
    reason: Option[String] = None,
    correlationId: Option[UUID] = None
)

/** Identifier of a recorded entry (`dms.audit_event.seq`). */
opaque type AuditRecordId = Long
object AuditRecordId:
  def apply(value: Long): AuditRecordId         = value
  extension (id: AuditRecordId) def value: Long = id
