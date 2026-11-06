import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'

/** Longest reason the backend accepts (`DestructionService.MaxReasonLength`). */
const MAX_REASON = 4000

/** The required reason of every proposal and decision; recorded in the audit trail. */
export function ReasonField({
  id,
  value,
  onChange,
  invalid,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  invalid: boolean
}) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{t('retention.reasonField')}</Label>
      <Textarea
        id={id}
        value={value}
        maxLength={MAX_REASON}
        onChange={(e) => {
          onChange(e.target.value)
        }}
        aria-invalid={invalid || undefined}
        aria-required
        aria-describedby={`${id}-hint`}
      />
      <p
        id={`${id}-hint`}
        role={invalid ? 'alert' : undefined}
        className={invalid ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
      >
        {invalid ? t('retention.reasonMissing') : t('retention.reasonHint', { max: MAX_REASON })}
      </p>
    </div>
  )
}
