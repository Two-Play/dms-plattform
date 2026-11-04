import { RETENTION_TRIGGERS, type RetentionRuleSummary } from '@/api/retention'
import { t } from '@/i18n'

/** A retention period in whole years if it is one, otherwise in months. */
export function periodText(months: number): string {
  return months % 12 === 0
    ? t('retention.rule.years', { count: months / 12 })
    : t('retention.rule.months', { count: months })
}

/**
 * A retention rule for people (DMS-133): name, period and start, e.g. "Richtlinien – 10 Jahre ab
 * Ende der Gültigkeit"; the key with a note if no rule has it.
 */
export function ruleText(rule: RetentionRuleSummary): string {
  if (rule.name === undefined || rule.periodMonths === undefined) {
    return t('retention.rule.unknown', { key: rule.key })
  }
  const trigger = RETENTION_TRIGGERS.find((tr) => tr === rule.trigger) ?? 'EndOfValidity'
  return t('retention.rule.text', {
    name: rule.name,
    period: periodText(rule.periodMonths),
    from: t(`retention.rule.from.${trigger}`),
    yearEnd: rule.fromYearEnd ? t('retention.rule.yearEnd') : '',
  })
}
