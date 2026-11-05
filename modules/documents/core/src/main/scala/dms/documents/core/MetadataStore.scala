package dms.documents.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.documents.ReviewPolicy
import dms.documents.metadata.*
import dms.documents.core.MetadataJson.given
import zio.json.*

import java.util.UUID

/** SQL for field definitions and document classes (migration V7). Runs inside `Db.sql` within
  * `Db.transact(tenant)`; row-level security limits all rows to the current tenant. Rows are only inserted.
  */
private[core] object MetadataStore:

  private type FieldRow = (String, Int, String, Option[String], Jsonb, Boolean)

  def latestFields()(using DbTx): List[FieldDefinition] =
    sql"""select distinct on (key) key, version, label, description, field_type, multiple from dms.field_definition order by key, version desc"""
      .query[FieldRow]
      .run()
      .map(toField)
      .toList

  def fieldVersions(key: FieldKey)(using DbTx): List[FieldDefinition] =
    sql"""select key, version, label, description, field_type, multiple from dms.field_definition where key = ${key.value} order by version"""
      .query[FieldRow]
      .run()
      .map(toField)
      .toList

  def field(key: FieldKey, version: Int)(using DbTx): Option[FieldDefinition] =
    sql"""select key, version, label, description, field_type, multiple from dms.field_definition where key = ${key.value} and version = $version"""
      .query[FieldRow]
      .run()
      .headOption
      .map(toField)

  def insertField(definition: FieldDefinition, createdBy: String)(using DbTx): Int =
    sql"""insert into dms.field_definition (tenant_id, key, version, label, description, field_type, multiple, created_by)
          values (dms.current_tenant(), ${definition.key.value}, ${definition.version}, ${definition.label},
                  ${definition.description}, ${Jsonb(definition.fieldType.toJson)}, ${definition.multiple},
                  $createdBy)""".update.run()

  private def toField(row: FieldRow): FieldDefinition =
    val (key, version, label, description, fieldType, multiple) = row
    FieldDefinition(
      FieldKey.unsafe(key),
      version,
      label,
      description,
      decode[FieldType](fieldType, s"field_type of $key version $version"),
      multiple,
      FieldOrigin.Tenant
    )

  private type ClassRow =
    (UUID, String, Int, Option[UUID], Option[Int], String, Option[String], Jsonb, Option[Int], Int, Boolean)

  /** The latest version of every class. */
  def latestClasses()(using DbTx): List[DocumentClassVersion] =
    sql"""select distinct on (c.key) c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days, dms.class_version_inherits_review(v)
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          order by c.key, v.version desc"""
      .query[ClassRow]
      .run()
      .map(toClass)
      .toList

  def classVersions(id: DocumentClassId)(using DbTx): List[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days, dms.class_version_inherits_review(v)
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          where c.id = ${id.value} order by v.version""".query[ClassRow].run().map(toClass).toList

  def classVersion(id: DocumentClassId, version: Int)(using DbTx): Option[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days, dms.class_version_inherits_review(v)
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          where c.id = ${id.value} and v.version = $version"""
      .query[ClassRow]
      .run()
      .headOption
      .map(toClass)

  def latestClassVersion(id: DocumentClassId)(using DbTx): Option[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days, dms.class_version_inherits_review(v)
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          where c.id = ${id.value} order by v.version desc limit 1"""
      .query[ClassRow]
      .run()
      .headOption
      .map(toClass)

  def insertClass(id: DocumentClassId, key: ClassKey, parent: Option[DocumentClassId], createdBy: String)(
      using DbTx
  ): Int =
    sql"""insert into dms.document_class (id, tenant_id, key, parent_id, created_by)
          values (${id.value}, dms.current_tenant(), ${key.value}, ${parent.map(
        _.value
      )}, $createdBy)""".update
      .run()

  def insertClassVersion(cls: DocumentClassVersion, createdBy: String)(using DbTx): Int =
    sql"""insert into dms.document_class_version
            (tenant_id, class_id, version, parent_version, label, description, fields, review_interval_months,
             review_lead_days, review_inherited, created_by)
          values (dms.current_tenant(), ${cls.classId.value}, ${cls.version}, ${cls.parent.map(_.version)},
                  ${cls.label}, ${cls.description}, ${Jsonb(cls.fields.toJson)}, ${cls.review.intervalMonths},
                  ${cls.review.leadDays}, ${cls.review.inherited}, $createdBy)""".update.run()

  /** The review settings in force for a class, its own or those of the nearest ancestor (ADR 0068). */
  def effectiveReviewPolicy(id: DocumentClassId)(using DbTx): Option[ReviewPolicy] =
    sql"select interval_months, lead_days from dms.class_review_policy(${id.value})"
      .query[(Option[Int], Int)]
      .run()
      .headOption
      .map(ReviewPolicy(_, _))

  /** The review settings in force for every class. */
  def effectiveReviewPolicies()(using DbTx): Map[DocumentClassId, ReviewPolicy] =
    sql"""select c.id, p.interval_months, p.lead_days
          from dms.document_class c cross join lateral dms.class_review_policy(c.id) p"""
      .query[(UUID, Option[Int], Int)]
      .run()
      .map((id, interval, leadDays) => DocumentClassId(id) -> ReviewPolicy(interval, leadDays))
      .toMap

  /** The own retention rule assignments of all classes (DMS-133, V37). */
  def retentionAssignments()(using DbTx): List[(UUID, String, String, java.time.Instant)] =
    sql"""select class_id, rule_key, updated_by, updated_at from dms.class_retention_rule"""
      .query[(UUID, String, String, java.time.OffsetDateTime)]
      .run()
      .map((id, rule, by, at) => (id, rule, by, at.toInstant))
      .toList

  /** The retention rule of a class: its own assignment or that of the nearest ancestor. */
  def effectiveRetentionRule(id: DocumentClassId)(using DbTx): Option[String] =
    sql"""with recursive chain (class_id, parent_id, depth) as (
                 select id, parent_id, 0 from dms.document_class where id = ${id.value}
                 union all
                 select c.id, c.parent_id, chain.depth + 1
                 from dms.document_class c join chain on c.id = chain.parent_id
                 where chain.depth < 64)
          select r.rule_key from chain join dms.class_retention_rule r on r.class_id = chain.class_id
          order by chain.depth limit 1""".query[String].run().headOption

  /** Whether a retention rule with this key exists (`dms.retention_rule`, V33). */
  def retentionRuleExists(key: String)(using DbTx): Boolean =
    sql"select count(*) from dms.retention_rule where key = $key".query[Long].run().head > 0

  /** Sets or replaces the own retention rule of a class; returns the previous one. */
  def assignRetentionRule(id: DocumentClassId, rule: String, actor: String)(using DbTx): Option[String] =
    val previous = ownRetentionRule(id)
    val _        = sql"""insert into dms.class_retention_rule (tenant_id, class_id, rule_key, updated_by)
          values (dms.current_tenant(), ${id.value}, $rule, $actor)
          on conflict (tenant_id, class_id) do update
            set rule_key = excluded.rule_key, updated_by = excluded.updated_by, updated_at = now()""".update
      .run()
    previous

  /** Removes the own retention rule of a class (it inherits again); returns the removed one. */
  def removeRetentionRule(id: DocumentClassId)(using DbTx): Option[String] =
    val previous = ownRetentionRule(id)
    val _        = sql"delete from dms.class_retention_rule where class_id = ${id.value}".update.run()
    previous

  /** The retention rule records management set for a document (DMS-133, V37). */
  def documentRetentionRule(id: dms.kernel.DocumentId)(using DbTx): Option[String] =
    sql"select rule_key from dms.document_retention_rule where document_id = ${id.value}"
      .query[String]
      .run()
      .headOption

  /** Sets or replaces the retention rule of a document; returns the previous one. */
  def setDocumentRetentionRule(id: dms.kernel.DocumentId, rule: String, actor: String)(using
      DbTx
  ): Option[String] =
    val previous = lockedDocumentRule(id)
    val _        = sql"""insert into dms.document_retention_rule (tenant_id, document_id, rule_key, updated_by)
          values (dms.current_tenant(), ${id.value}, $rule, $actor)
          on conflict (tenant_id, document_id) do update
            set rule_key = excluded.rule_key, updated_by = excluded.updated_by, updated_at = now()""".update
      .run()
    previous

  /** Removes the retention rule of a document (it follows its class again); returns the removed one. */
  def removeDocumentRetentionRule(id: dms.kernel.DocumentId)(using DbTx): Option[String] =
    val previous = lockedDocumentRule(id)
    val _        = sql"delete from dms.document_retention_rule where document_id = ${id.value}".update.run()
    previous

  private def lockedDocumentRule(id: dms.kernel.DocumentId)(using DbTx): Option[String] =
    sql"select rule_key from dms.document_retention_rule where document_id = ${id.value} for update"
      .query[String]
      .run()
      .headOption

  private def ownRetentionRule(id: DocumentClassId)(using DbTx): Option[String] =
    sql"select rule_key from dms.class_retention_rule where class_id = ${id.value} for update"
      .query[String]
      .run()
      .headOption

  private def toClass(row: ClassRow): DocumentClassVersion =
    val (
      id,
      key,
      version,
      parentId,
      parentVersion,
      label,
      description,
      fields,
      interval,
      leadDays,
      inherited
    ) =
      row
    DocumentClassVersion(
      DocumentClassId(id),
      ClassKey.unsafe(key),
      version,
      parentId.zip(parentVersion).map((p, v) => ClassRef(DocumentClassId(p), v)),
      label,
      description,
      decode[List[FieldBinding]](fields, s"fields of class $key version $version"),
      ReviewPolicy(interval, leadDays, inherited)
    )

  private def decode[A: JsonDecoder](json: Jsonb, what: String): A =
    json.value.fromJson[A].fold(e => throw IllegalStateException(s"cannot read $what: $e"), identity)
