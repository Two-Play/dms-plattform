package dms.documents.core

import com.augustnagro.magnum.*
import dms.documents.*
import dms.kernel.{DocumentId, UserId, VersionNo}

import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** SQL for the periodic review (migration V18): the review date of valid versions and the review tasks. Runs
  * inside `Db.sql` within `Db.transact(tenant)`; row-level security limits all rows to the current tenant.
  */
private[core] object ReviewStore:

  private given DbCodec[LocalDate] =
    DbCodec[java.sql.Date].biMap(d => if d == null then null else d.toLocalDate, java.sql.Date.valueOf)

  final case class TaskRow(
      id: UUID,
      documentId: UUID,
      versionId: UUID,
      dueOn: LocalDate,
      createdAt: OffsetDateTime,
      status: String,
      decidedAt: Option[OffsetDateTime],
      decidedBy: Option[String],
      nextDueOn: Option[LocalDate],
      newVersionId: Option[UUID],
      comment: Option[String]
  ) derives DbCodec:
    def toTask: ReviewTask =
      ReviewTask(
        id,
        DocumentId(documentId),
        versionId,
        dueOn,
        createdAt.toInstant,
        ReviewStatus.valueOf(status),
        decidedAt.map(_.toInstant),
        decidedBy.map(UserId(_)),
        nextDueOn,
        newVersionId,
        comment
      )

  /** A valid version due for its review task or review date, with the lead time of its class. */
  final case class Candidate(
      versionId: UUID,
      documentId: UUID,
      status: String,
      dueOn: LocalDate,
      leadDays: Int
  ) derives DbCodec

  /** The review state of a version, locked: status, review date and the lead time of its class. */
  def lockVersion(id: UUID)(using DbTx): Option[Candidate] =
    sql"""select v.id, v.document_id, v.status, v.review_due_on, c.review_lead_days
          from dms.document_version v
          join dms.document d on d.id = v.document_id
          cross join lateral (select review_lead_days from dms.document_class_version cv
                              where cv.class_id = d.class_id order by cv.version desc limit 1) c
          where v.id = $id and v.review_due_on is not null
          for update of v"""
      .query[Candidate]
      .run()
      .headOption

  /** Valid versions whose review task is due on `today` (lead time of the class reached) and does not exist
    * yet for their current review date.
    */
  def tasksToOpen(today: LocalDate)(using DbTx): List[Candidate] =
    sql"""select v.id, v.document_id, v.status, v.review_due_on, c.review_lead_days
          from dms.document_version v
          join dms.document d on d.id = v.document_id
          cross join lateral (select review_lead_days from dms.document_class_version cv
                              where cv.class_id = d.class_id order by cv.version desc limit 1) c
          where v.status in ('Published', 'ReviewDue')
            and v.review_due_on is not null
            and v.review_due_on - c.review_lead_days <= $today
            and not exists (select 1 from dms.document_review r
                            where r.version_id = v.id and r.due_on = v.review_due_on)
          order by v.review_due_on, v.id"""
      .query[Candidate]
      .run()
      .toList

  /** Published versions whose review date has come. */
  def reviewDateReached(today: LocalDate)(using DbTx): List[UUID] =
    sql"""select id from dms.document_version
          where status = 'Published' and review_due_on <= $today order by review_due_on, id"""
      .query[UUID]
      .run()
      .toList

  /** Open tasks whose version is not valid any more. */
  def obsoleteTasks()(using DbTx): List[UUID] =
    sql"""select r.id from dms.document_review r join dms.document_version v on v.id = r.version_id
          where r.status = 'Open' and v.status not in ('Published', 'ReviewDue') order by r.created_at, r.id"""
      .query[UUID]
      .run()
      .toList

  /** Creates the open task of a version and review date; `false` if it exists already (idempotent). */
  def insertTask(id: UUID, document: DocumentId, version: UUID, dueOn: LocalDate, at: Instant)(using
      DbTx
  ): Boolean =
    sql"""insert into dms.document_review (id, tenant_id, document_id, version_id, due_on, created_at)
          values ($id, dms.current_tenant(), ${document.value}, $version, $dueOn, ${utc(at)})
          on conflict do nothing""".update.run() == 1

  def task(id: UUID)(using DbTx): Option[ReviewTask] =
    sql"""select id, document_id, version_id, due_on, created_at, status, decided_at, decided_by, next_due_on,
                 new_version_id, comment
          from dms.document_review where id = $id""".query[TaskRow].run().headOption.map(_.toTask)

  def lockTask(id: UUID)(using DbTx): Option[ReviewTask] =
    sql"""select id, document_id, version_id, due_on, created_at, status, decided_at, decided_by, next_due_on,
                 new_version_id, comment
          from dms.document_review where id = $id for update""".query[TaskRow].run().headOption.map(_.toTask)

  /** Review tasks of the documents `owner` is the owner of: open ones (of valid versions) by review date, or
    * decided ones, the latest first.
    */
  def tasksOfOwner(owner: UserId, open: Boolean, limit: Int)(using DbTx): List[ReviewTask] =
    (if open then
       sql"""select r.id, r.document_id, r.version_id, r.due_on, r.created_at, r.status, r.decided_at,
                    r.decided_by, r.next_due_on, r.new_version_id, r.comment
             from dms.document_review r
             join dms.document d on d.id = r.document_id
             join dms.document_version v on v.id = r.version_id
             where d.owner_id = ${owner.value} and r.status = 'Open' and v.status in ('Published', 'ReviewDue')
             order by r.due_on, r.created_at, r.id
             limit $limit""".query[TaskRow].run()
     else sql"""select r.id, r.document_id, r.version_id, r.due_on, r.created_at, r.status, r.decided_at,
                    r.decided_by, r.next_due_on, r.new_version_id, r.comment
             from dms.document_review r
             join dms.document d on d.id = r.document_id
             where d.owner_id = ${owner.value} and r.status <> 'Open'
             order by r.decided_at desc, r.id
             limit $limit""".query[TaskRow].run()).map(_.toTask).toList

  /** Every review task of a document, the earliest first (evidence reports, DMS-57). */
  def tasksOfDocument(document: DocumentId)(using DbTx): List[ReviewTask] =
    sql"""select id, document_id, version_id, due_on, created_at, status, decided_at, decided_by, next_due_on,
                 new_version_id, comment
          from dms.document_review where document_id = ${document.value}
          order by created_at, id""".query[TaskRow].run().map(_.toTask).toList

  /** Records the decision on an open task; `false` if it was decided already. */
  def decide(task: ReviewTask)(using DbTx): Boolean =
    sql"""update dms.document_review
          set status = ${task.status.toString}, decided_at = ${task.decidedAt.map(utc)},
              decided_by = ${task.decidedBy.map(_.value)}, next_due_on = ${task.nextDueOn},
              new_version_id = ${task.newVersionId}, comment = ${task.comment}
          where id = ${task.id} and status = 'Open'""".update.run() == 1

  /** Sets the review date of a valid version (on publication and on recertification). */
  def setDueOn(version: UUID, dueOn: LocalDate)(using DbTx): Boolean =
    sql"""update dms.document_version set review_due_on = $dueOn
          where id = $version and status in ('Published', 'ReviewDue')""".update.run() == 1

  /** The review date of a version, if it has one. */
  def dueOn(version: UUID)(using DbTx): Option[LocalDate] =
    sql"select review_due_on from dms.document_version where id = $version"
      .query[Option[LocalDate]]
      .run()
      .headOption
      .flatten

  def versionStatus(id: UUID)(using DbTx): Option[DocumentStatus] =
    sql"select status from dms.document_version where id = $id"
      .query[String]
      .run()
      .headOption
      .map(DocumentStatus.valueOf)

  /** The label of a version, e.g. `2.0`, for the audit trail. */
  def versionLabel(id: UUID)(using DbTx): Option[String] =
    sql"select major, minor, status, working_state from dms.document_version where id = $id"
      .query[(Int, Int, String, Int)]
      .run()
      .headOption
      .map((major, minor, status, workingState) =>
        Versioning.label(VersionNo(major, minor), DocumentStatus.valueOf(status), workingState)
      )

  private def utc(at: Instant): OffsetDateTime = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
