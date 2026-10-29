import type { FieldValue } from '@/api/documents'
import type { Partner, ReferenceField } from '@/api/partners'
import type { MetadataValue, TemplateField } from '@/api/templates'
import { t } from '@/i18n'
import { formatNumber, formatPlainDate } from '@/i18n/format'
import { initialInput, initialInputs, type FieldInput, type FormInputs } from './field-values'

/** The text of a single stored value as the form shows it (inverse of the form's conversion). */
function singleText(value: MetadataValue): string {
  switch (value.type) {
    case 'Text':
    case 'Date':
      return value.value
    case 'Number':
      return String(value.value)
    case 'Amount':
      return String(value.value)
    case 'Selection':
      return value.code
    case 'YesNo':
      return value.value ? 'true' : 'false'
    case 'PersonRef':
      return value.userId
    case 'DocumentRef':
      return value.documentId
    case 'Many':
      return value.values.map(singleText).join('\n')
  }
}

/** The form input for a stored value of `field`; values that do not fit the field leave it empty. */
export function inputFromValue(field: TemplateField, value: MetadataValue): FieldInput {
  if (field.multiple) {
    const items = value.type === 'Many' ? value.values : [value]
    return field.fieldType.type === 'Selection'
      ? items.flatMap((v) => (v.type === 'Selection' ? [v.code] : []))
      : items.map(singleText).join('\n')
  }
  if (field.fieldType.type === 'Amount') {
    return value.type === 'Amount'
      ? { amount: String(value.value), currency: value.currency }
      : initialInput(field)
  }
  return value.type === 'Many' ? initialInput(field) : singleText(value)
}

/** A stored value as plain text for reading, e.g. `1250.5 EUR` or `Yes`. */
export function displayValue(value: MetadataValue): string {
  switch (value.type) {
    case 'Amount':
      return `${formatNumber(value.value)} ${value.currency}`
    case 'Number':
      return formatNumber(value.value)
    case 'Date':
      return formatPlainDate(value.value)
    case 'YesNo':
      return value.value ? t('common.yes') : t('common.no')
    case 'Many':
      return value.values.map(displayValue).join(', ')
    default:
      return singleText(value)
  }
}

/**
 * The form inputs of a saved draft: its entered (manual) values. Values the platform sets, computed values
 * and values taken over from a business system are not entered; the latter come back through the
 * reference fields (`partnerFromDraft`).
 */
export function inputsFromDraft(
  fields: TemplateField[],
  values: Record<string, FieldValue>,
): FormInputs {
  const inputs = initialInputs(fields)
  for (const field of fields) {
    const stored = values[field.key]
    if (stored?.origin !== 'Manual' || field.reference) continue
    inputs[field.key] = inputFromValue(field, stored.value)
  }
  return inputs
}

function text(value: FieldValue | undefined): string | undefined {
  return value?.origin === 'System' && value.value.type === 'Text' ? value.value.value : undefined
}

/**
 * The partner taken over into a saved draft for `reference`, rebuilt from the values it filled; undefined
 * if none was taken over. Only mapped attributes are known, so the others stay empty.
 */
export function partnerFromDraft(
  reference: ReferenceField,
  values: Record<string, FieldValue>,
): Partner | undefined {
  const attributes: Record<string, string> = {}
  for (const mapping of reference.fields ?? []) {
    const value = text(values[mapping.field])
    if (value !== undefined) attributes[mapping.attribute] = value
  }
  if (Object.keys(attributes).length === 0) return undefined
  const address = [
    attributes.street,
    [attributes.postalCode, attributes.city].filter(Boolean).join(' '),
  ]
    .filter(Boolean)
    .join(', ')
  return {
    id: attributes.id ?? '',
    name: attributes.name ?? Object.values(attributes)[0] ?? '',
    street: attributes.street,
    postalCode: attributes.postalCode,
    city: attributes.city,
    country: attributes.country,
    address: attributes.address ?? address,
    vatId: attributes.vatId,
  }
}
