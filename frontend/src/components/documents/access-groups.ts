import type { MetadataValue, TemplateField } from '@/api/templates'
import { t } from '@/i18n'
import type { FieldInput, FormInputs } from './field-values'

/**
 * Access to confidential documents (DMS-101, ADR 0032): from confidentiality "Confidential" on, only the
 * author, the owner and members of the groups in the core field `allowedGroups` see a version. The backend
 * requires at least one group when such a version is submitted.
 */
export const CONFIDENTIALITY_FIELD = 'confidentiality'
export const ALLOWED_GROUPS_FIELD = 'allowedGroups'

const RESTRICTED = new Set(['Confidential', 'StrictlyConfidential'])

/** Whether a confidentiality code restricts access to named groups. */
export function isRestrictedLevel(code: string | undefined): boolean {
  return code !== undefined && RESTRICTED.has(code)
}

/** Whether the form input of the confidentiality field restricts access to named groups. */
export function isRestrictedInput(input: FieldInput | undefined): boolean {
  return typeof input === 'string' && isRestrictedLevel(input.trim())
}

/** Group keys of the form input of `allowedGroups` (one key per line, like other multiple text fields). */
export function groupKeys(input: FieldInput | undefined): string[] {
  if (Array.isArray(input)) return input
  if (typeof input !== 'string') return []
  return input
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/** The form input of `allowedGroups` for a list of group keys. */
export function groupsInput(keys: string[]): FieldInput {
  return keys.join('\n')
}

/** The confidentiality code of stored values, if any. */
export function confidentialityOf(
  values: Record<string, MetadataValue | undefined>,
): string | undefined {
  const value = values[CONFIDENTIALITY_FIELD]
  if (value?.type === 'Selection') return value.code
  if (value?.type === 'Text') return value.value
  return undefined
}

/** The group keys of stored values. */
export function allowedGroupsOf(values: Record<string, MetadataValue | undefined>): string[] {
  const value = values[ALLOWED_GROUPS_FIELD]
  const items = value?.type === 'Many' ? value.values : value ? [value] : []
  return items.flatMap((v) =>
    v.type === 'Text' ? [v.value] : v.type === 'Selection' ? [v.code] : [],
  )
}

/** Values without the allowed groups unless the confidentiality restricts access: hidden groups are not sent. */
export function withoutHiddenGroups<T>(
  values: Record<string, T>,
  restricted: boolean,
): Record<string, T> {
  if (restricted || !(ALLOWED_GROUPS_FIELD in values)) return values
  return Object.fromEntries(Object.entries(values).filter(([key]) => key !== ALLOWED_GROUPS_FIELD))
}

/**
 * The fields the form shows: the allowed groups only while the confidentiality restricts access (DMS-101),
 * then required, since a confidential version cannot be submitted without them.
 */
export function visibleFields(fields: TemplateField[], inputs: FormInputs): TemplateField[] {
  const restricted = isRestrictedInput(inputs[CONFIDENTIALITY_FIELD])
  return fields.flatMap((field) => {
    if (field.key !== ALLOWED_GROUPS_FIELD) return [field]
    if (!restricted) return []
    return [
      {
        ...field,
        required: true,
        description: t('docs.form.allowedGroupsHint'),
      },
    ]
  })
}
