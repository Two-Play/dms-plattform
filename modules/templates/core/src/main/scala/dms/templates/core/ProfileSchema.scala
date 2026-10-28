package dms.templates.core

import dms.documents.DocumentStatus
import dms.documents.metadata.*
import dms.templates.*

import java.util.UUID
import scala.util.Try

/** Connects templates to the metadata model (ADR 0019): the fields of a document class profile version become
  * the variables of a template, metadata values become field values.
  */
object ProfileSchema:

  /** A field is required for templates if a document cannot be approved without it. */
  val RequiredFor: DocumentStatus = DocumentStatus.Approved

  def from(profile: MetadataProfile): TemplateSchema =
    TemplateSchema(profile.fields.map { f =>
      val element = valueType(f.definition.fieldType)
      Variable(
        f.key.value,
        if f.definition.multiple then ValueType.ListOf(element) else element,
        f.isRequiredIn(RequiredFor),
        Some(f.definition.label)
      )
    })

  def valueType(fieldType: FieldType): ValueType = fieldType match
    case FieldType.Text(_, _)         => ValueType.Text
    case FieldType.Number(_, _, _)    => ValueType.Number
    case FieldType.Amount(_, _, _)    => ValueType.Amount
    case FieldType.Date(_, _)         => ValueType.Date
    case FieldType.Selection(options) => ValueType.Selection(options.map(o => Choice(o.code, o.label)))
    case FieldType.YesNo              => ValueType.YesNo
    case FieldType.PersonRef          => ValueType.PersonRef
    case FieldType.DocumentRef        => ValueType.DocumentRef

  def fieldValue(value: MetadataValue): FieldValue = value match
    case MetadataValue.Text(v)           => FieldValue.Text(v)
    case MetadataValue.Number(v)         => FieldValue.Number(v)
    case MetadataValue.Amount(v, c)      => FieldValue.Amount(v, c)
    case MetadataValue.Date(v)           => FieldValue.Date(v)
    case MetadataValue.Selection(code)   => FieldValue.Text(code)
    case MetadataValue.YesNo(v)          => FieldValue.YesNo(v)
    case MetadataValue.PersonRef(userId) => FieldValue.Text(userId)
    case MetadataValue.DocumentRef(id)   => FieldValue.Text(id.toString)
    case MetadataValue.Many(values)      => FieldValue.ListOf(values.map(fieldValue))

  /** The metadata value of a field value of type `valueType`; `None` if it does not fit. */
  def metadataValue(value: FieldValue, valueType: ValueType): Option[MetadataValue] = (value, valueType) match
    case (FieldValue.Text(v), ValueType.Text)         => Some(MetadataValue.Text(v))
    case (FieldValue.Text(v), ValueType.Selection(_)) => Some(MetadataValue.Selection(v))
    case (FieldValue.Text(v), ValueType.PersonRef)    => Some(MetadataValue.PersonRef(v))
    case (FieldValue.Text(v), ValueType.DocumentRef)  =>
      Try(UUID.fromString(v)).toOption.map(MetadataValue.DocumentRef(_))
    case (FieldValue.Number(v), ValueType.Number)     => Some(MetadataValue.Number(v))
    case (FieldValue.Amount(v, c), ValueType.Amount)  => Some(MetadataValue.Amount(v, c))
    case (FieldValue.Date(v), ValueType.Date)         => Some(MetadataValue.Date(v))
    case (FieldValue.YesNo(v), ValueType.YesNo)       => Some(MetadataValue.YesNo(v))
    case (FieldValue.ListOf(vs), ValueType.ListOf(e)) =>
      val converted = vs.flatMap(metadataValue(_, e))
      Option.when(converted.size == vs.size)(MetadataValue.Many(converted))
    case _ => None
