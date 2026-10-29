import type { NewDeputyship } from '@/api/deputyships'
import { t } from '@/i18n'

export interface DeputyshipInput {
  principal: string
  deputy: string
  /** `datetime-local` values in the browser's time zone; empty `validFrom` means now. */
  validFrom: string
  validUntil: string
  roles: string[]
  documentClass: string
  reason: string
}

export const emptyDeputyshipInput: DeputyshipInput = {
  principal: '',
  deputy: '',
  validFrom: '',
  validUntil: '',
  roles: [],
  documentClass: '',
  reason: '',
}

export type Problems = Partial<Record<'deputy' | 'validFrom' | 'validUntil', string>>

/** Client-side checks before sending; the backend checks authority and the maximum length too. */
export function checkDeputyship(input: DeputyshipInput, now: Date = new Date()): Problems {
  const problems: Problems = {}
  if (input.deputy.trim() === '') problems.deputy = t('deputies.deputyMissing')
  else if (input.deputy.trim() === input.principal.trim()) {
    problems.deputy = t('deputies.ownDeputy')
  }
  const from = input.validFrom === '' ? now : new Date(input.validFrom)
  if (Number.isNaN(from.getTime())) problems.validFrom = t('deputies.invalidStart')
  if (input.validUntil === '') problems.validUntil = t('deputies.endMissing')
  else {
    const until = new Date(input.validUntil)
    if (Number.isNaN(until.getTime())) problems.validUntil = t('deputies.invalidEnd')
    else if (until.getTime() <= Math.max(from.getTime(), now.getTime())) {
      problems.validUntil = t('deputies.endInPast')
    }
  }
  return problems
}

/** The request body: blank optional values are left out so the backend applies its defaults. */
export function toRequest(input: DeputyshipInput): NewDeputyship {
  const optional = (value: string) => (value.trim() === '' ? undefined : value.trim())
  return {
    principal: optional(input.principal),
    deputy: input.deputy.trim(),
    validFrom: input.validFrom === '' ? undefined : new Date(input.validFrom).toISOString(),
    validUntil: new Date(input.validUntil).toISOString(),
    roles: input.roles.length > 0 ? input.roles : undefined,
    documentClass: optional(input.documentClass),
    reason: optional(input.reason),
  }
}
