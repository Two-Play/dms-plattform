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
    (UUID, String, Int, Option[UUID], Option[Int], String, Option[String], Jsonb, Option[Int], Int)

  /** The latest version of every class. */
  def latestClasses()(using DbTx): List[DocumentClassVersion] =
    sql"""select distinct on (c.key) c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          order by c.key, v.version desc"""
      .query[ClassRow]
      .run()
      .map(toClass)
      .toList

  def classVersions(id: DocumentClassId)(using DbTx): List[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          where c.id = ${id.value} order by v.version""".query[ClassRow].run().map(toClass).toList

  def classVersion(id: DocumentClassId, version: Int)(using DbTx): Option[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days
          from dms.document_class_version v join dms.document_class c on c.id = v.class_id
          where c.id = ${id.value} and v.version = $version"""
      .query[ClassRow]
      .run()
      .headOption
      .map(toClass)

  def latestClassVersion(id: DocumentClassId)(using DbTx): Option[DocumentClassVersion] =
    sql"""select c.id, c.key, v.version, c.parent_id, v.parent_version, v.label, v.description, v.fields,
                 v.review_interval_months, v.review_lead_days
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
             review_lead_days, created_by)
          values (dms.current_tenant(), ${cls.classId.value}, ${cls.version}, ${cls.parent.map(_.version)},
                  ${cls.label}, ${cls.description}, ${Jsonb(cls.fields.toJson)}, ${cls.review.intervalMonths},
                  ${cls.review.leadDays}, $createdBy)""".update.run()

  private def toClass(row: ClassRow): DocumentClassVersion =
    val (id, key, version, parentId, parentVersion, label, description, fields, interval, leadDays) = row
    DocumentClassVersion(
      DocumentClassId(id),
      ClassKey.unsafe(key),
      version,
      parentId.zip(parentVersion).map((p, v) => ClassRef(DocumentClassId(p), v)),
      label,
      description,
      decode[List[FieldBinding]](fields, s"fields of class $key version $version"),
      ReviewPolicy(interval, leadDays)
    )

  private def decode[A: JsonDecoder](json: Jsonb, what: String): A =
    json.value.fromJson[A].fold(e => throw IllegalStateException(s"cannot read $what: $e"), identity)
