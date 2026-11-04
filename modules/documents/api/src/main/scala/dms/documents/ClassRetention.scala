package dms.documents

import dms.documents.metadata.{ClassKey, DocumentClassId}
import dms.kernel.UserId

import java.time.Instant

/** The retention rule a document class assigns to its documents (DMS-133, addendum to ADR 0054).
  *
  * @param ruleKey
  *   key of the rule (`dms.retention_rule`) the class itself assigns; `None`: inherited or none
  * @param effectiveRule
  *   the rule documents of the class get: its own, or the nearest ancestor's
  * @param inheritedFrom
  *   the ancestor the effective rule comes from, if not assigned by the class itself
  */
final case class ClassRetention(
    classId: DocumentClassId,
    classKey: ClassKey,
    label: String,
    parent: Option[DocumentClassId],
    ruleKey: Option[String],
    effectiveRule: Option[String],
    inheritedFrom: Option[ClassKey],
    updatedBy: Option[UserId],
    updatedAt: Option[Instant]
)

object ClassRetention:
  /** Key of the core field naming the retention rule of a version. */
  val Field = "retentionRule"

  /** The effective rule of every class: its own assignment or the nearest ancestor's. `classes` are
    * `(id, key, label, parent)`, `assigned` the own assignments by class.
    */
  def resolve(
      classes: List[(DocumentClassId, ClassKey, String, Option[DocumentClassId])],
      assigned: Map[DocumentClassId, (String, UserId, Instant)]
  ): List[ClassRetention] =
    val byId = classes.map(c => c._1 -> c).toMap
    def effective(id: DocumentClassId, seen: Set[DocumentClassId]): Option[(String, DocumentClassId)] =
      if seen.contains(id) then None
      else
        assigned.get(id) match
          case Some((rule, _, _)) => Some(rule -> id)
          case None => byId.get(id).flatMap(_._4).flatMap(parent => effective(parent, seen + id))
    classes.map { (id, key, label, parent) =>
      val own   = assigned.get(id)
      val found = effective(id, Set.empty)
      ClassRetention(
        id,
        key,
        label,
        parent,
        own.map(_._1),
        found.map(_._1),
        found.collect { case (_, from) if from != id => byId.get(from).map(_._2) }.flatten,
        own.map(_._2),
        own.map(_._3)
      )
    }
