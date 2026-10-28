import type { FieldType, MetadataValue, TemplateField } from '@/api/templates'

/**
 * Raw form input of a field, before conversion:
 * - single values: the input text (`''` when empty); yes/no as `'true'`/`'false'`; selections as the code
 * - amounts: amount text and currency code
 * - multiple selections: the selected codes; other multiple fields: one value per line
 */
export type FieldInput = string | string[] | { amount: string; currency: string }

export type FormInputs = Record<string, FieldInput>

/** Id of the control of a field; the preview focuses it when a missing value is clicked. */
export function fieldControlId(key: string): string {
  return `field-${key}`
}

/** Result of converting one field: a value, nothing (empty) or a validation message. */
export type Converted =
  { kind: 'value'; value: MetadataValue } | { kind: 'empty' } | { kind: 'invalid'; message: string }

export function initialInput(field: TemplateField): FieldInput {
  if (field.multiple) return field.fieldType.type === 'Selection' ? [] : ''
  if (field.fieldType.type === 'Amount') {
    return { amount: '', currency: field.fieldType.currencies?.[0] ?? 'EUR' }
  }
  return ''
}

export function initialInputs(fields: TemplateField[]): FormInputs {
  return Object.fromEntries(fields.map((f) => [f.key, initialInput(f)]))
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DATE = /^\d{4}-\d{2}-\d{2}$/
const CURRENCY = /^[A-Z]{3}$/

/** Parses a decimal number; accepts a comma as decimal separator. */
export function parseNumber(text: string): number | undefined {
  const normalized = text.trim().replace(',', '.')
  if (!/^-?\d+(\.\d+)?$/.test(normalized)) return undefined
  return Number(normalized)
}

function range(
  value: number,
  min: number | undefined,
  max: number | undefined,
): string | undefined {
  if (min !== undefined && value < min) return `Must be at least ${min}.`
  if (max !== undefined && value > max) return `Must be at most ${max}.`
  return undefined
}

function decimals(text: string): number {
  const fraction = text.trim().replace(',', '.').split('.')[1]
  return fraction?.length ?? 0
}

/** Converts and validates the text of a single value of `fieldType`, like the backend's field rules. */
function convertSingle(fieldType: FieldType, text: string): Converted {
  const trimmed = text.trim()
  if (trimmed === '') return { kind: 'empty' }
  switch (fieldType.type) {
    case 'Text': {
      if (fieldType.maxLength !== undefined && trimmed.length > fieldType.maxLength) {
        return { kind: 'invalid', message: `At most ${fieldType.maxLength} characters.` }
      }
      if (fieldType.pattern !== undefined) {
        let matches = true
        try {
          matches = new RegExp(`^(?:${fieldType.pattern})$`).test(trimmed)
        } catch {
          // A pattern JavaScript cannot compile is left to the backend.
        }
        if (!matches)
          return { kind: 'invalid', message: `Does not match the format ${fieldType.pattern}.` }
      }
      return { kind: 'value', value: { type: 'Text', value: trimmed } }
    }
    case 'Number': {
      const n = parseNumber(trimmed)
      if (n === undefined) return { kind: 'invalid', message: 'Enter a number, e.g. 12 or 12.5.' }
      const outside = range(n, fieldType.min, fieldType.max)
      if (outside) return { kind: 'invalid', message: outside }
      if (fieldType.scale !== undefined && decimals(trimmed) > fieldType.scale) {
        return { kind: 'invalid', message: `At most ${fieldType.scale} decimal places.` }
      }
      return { kind: 'value', value: { type: 'Number', value: n } }
    }
    case 'Date': {
      if (!DATE.test(trimmed) || Number.isNaN(Date.parse(trimmed))) {
        return { kind: 'invalid', message: 'Enter a date.' }
      }
      if (fieldType.min !== undefined && trimmed < fieldType.min) {
        return { kind: 'invalid', message: `Must not be before ${fieldType.min}.` }
      }
      if (fieldType.max !== undefined && trimmed > fieldType.max) {
        return { kind: 'invalid', message: `Must not be after ${fieldType.max}.` }
      }
      return { kind: 'value', value: { type: 'Date', value: trimmed } }
    }
    case 'Selection': {
      const known = fieldType.options?.some((o) => o.code === trimmed && o.active) ?? false
      if (!known) return { kind: 'invalid', message: 'Choose one of the options.' }
      return { kind: 'value', value: { type: 'Selection', code: trimmed } }
    }
    case 'YesNo':
      if (trimmed !== 'true' && trimmed !== 'false') {
        return { kind: 'invalid', message: 'Choose yes or no.' }
      }
      return { kind: 'value', value: { type: 'YesNo', value: trimmed === 'true' } }
    case 'PersonRef':
      return { kind: 'value', value: { type: 'PersonRef', userId: trimmed } }
    case 'DocumentRef':
      if (!UUID.test(trimmed)) return { kind: 'invalid', message: 'Enter a document id (UUID).' }
      return { kind: 'value', value: { type: 'DocumentRef', documentId: trimmed } }
    case 'Amount':
      return { kind: 'invalid', message: 'Enter an amount.' }
  }
}

function convertAmount(
  fieldType: Extract<FieldType, { type: 'Amount' }>,
  input: { amount: string; currency: string },
): Converted {
  if (input.amount.trim() === '') return { kind: 'empty' }
  const n = parseNumber(input.amount)
  if (n === undefined) return { kind: 'invalid', message: 'Enter an amount, e.g. 1250.50.' }
  const outside = range(n, fieldType.min, fieldType.max)
  if (outside) return { kind: 'invalid', message: outside }
  const currency = input.currency.trim().toUpperCase()
  if (!CURRENCY.test(currency))
    return { kind: 'invalid', message: 'Enter a currency code, e.g. EUR.' }
  const allowed = fieldType.currencies ?? []
  if (allowed.length > 0 && !allowed.includes(currency)) {
    return { kind: 'invalid', message: `Allowed currencies: ${allowed.join(', ')}.` }
  }
  return { kind: 'value', value: { type: 'Amount', value: n, currency } }
}

/** Converts the form input of a field into a metadata value, validating it against the field's rules. */
export function convert(field: TemplateField, input: FieldInput | undefined): Converted {
  const fieldType = field.fieldType
  if (input === undefined) return { kind: 'empty' }
  if (field.multiple) {
    const items = Array.isArray(input)
      ? input
      : typeof input === 'string'
        ? input.split('\n').filter((line) => line.trim() !== '')
        : []
    if (items.length === 0) return { kind: 'empty' }
    const values: MetadataValue[] = []
    for (const [index, item] of items.entries()) {
      const single = convertSingle(fieldType, item)
      if (single.kind === 'invalid') {
        return { kind: 'invalid', message: `Value ${index + 1}: ${single.message}` }
      }
      if (single.kind === 'value') values.push(single.value)
    }
    return { kind: 'value', value: { type: 'Many', values } }
  }
  if (fieldType.type === 'Amount') {
    return typeof input === 'object' && !Array.isArray(input)
      ? convertAmount(fieldType, input)
      : { kind: 'empty' }
  }
  return typeof input === 'string' ? convertSingle(fieldType, input) : { kind: 'empty' }
}

/** Valid, non-empty values keyed by field key (for the preview), and validation messages by field key. */
export function collectValues(
  fields: TemplateField[],
  inputs: FormInputs,
): { values: Record<string, MetadataValue>; errors: Record<string, string> } {
  const values: Record<string, MetadataValue> = {}
  const errors: Record<string, string> = {}
  for (const field of fields) {
    const result = convert(field, inputs[field.key])
    if (result.kind === 'value') values[field.key] = result.value
    if (result.kind === 'invalid') errors[field.key] = result.message
  }
  return { values, errors }
}
