package dms.documents.core

import com.augustnagro.magnum.*
import dms.db.Db
import dms.documents.metadata.DocumentClassId
import dms.kernel.UserId
import zio.*

/** Retention rules of document classes in tests (DMS-133): drafts of a class get its rule from the platform.
  */
object RetentionFixtures:
  val RecordsManager: UserId = UserId("records-manager")

  /** Creates the retention rule `key` (`months` after the end of validity) unless it exists. Runs inside
    * `Db.transact(tenant)`.
    */
  def rule(key: String, months: Int = 120): IO[MetadataError, Unit] =
    Db.sql(
      sql"""insert into dms.retention_rule
              (tenant_id, key, name, period_months, trigger_event, from_year_end, created_by, updated_by)
            values (dms.current_tenant(), $key, $key, $months, 'EndOfValidity', true, ${RecordsManager.value},
                    ${RecordsManager.value})
            on conflict do nothing""".update.run()
    ).mapError(MetadataError.Database(_))
      .unit

  /** Creates the retention rule `key` unless it exists and assigns it to the class. Runs inside
    * `Db.transact(tenant)`.
    */
  def assign(classes: DocumentClassService, classId: DocumentClassId, key: String): IO[MetadataError, Unit] =
    rule(key) *> classes.assignRetentionRule(classId, Some(key), RecordsManager).unit
