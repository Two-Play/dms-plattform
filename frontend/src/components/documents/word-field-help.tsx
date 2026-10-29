import { t } from '@/i18n'

/** How fields are marked in Word documents (DMS-115, ADR 0042): content controls and placeholders. */
export function WordFieldHelp() {
  return (
    <details className="rounded-md border bg-muted/40 p-3 text-xs" data-testid="word-field-help">
      <summary className="cursor-pointer font-medium">{t('word.help.title')}</summary>
      <ul className="mt-2 list-disc space-y-1 pl-4">
        <li>{t('word.help.controls')}</li>
        <li>{t('word.help.automatic')}</li>
        <li>{t('word.help.manual')}</li>
        <li>{t('word.help.placeholders', { manual: '{{key}}', automatic: '{{dms:key}}' })}</li>
      </ul>
    </details>
  )
}
