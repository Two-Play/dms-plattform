import { Link } from '@tanstack/react-router'
import { AlertTriangle } from 'lucide-react'
import { isResultChanged, type HoldSearchPreview } from '@/api/legal-holds'
import { InlineError } from '@/components/shared/error-state'
import { Badge } from '@/components/ui/badge'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'

/** Longest reason the backend accepts (`LegalHoldService.MaxReasonLength`). */
export const MAX_REASON = 4000

/** Longest name and matter reference (`LegalHoldService.MaxNameLength`). */
export const MAX_NAME = 200

/** The required reason of every legal hold change; recorded in the audit trail. */
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
      <Label htmlFor={id}>{t('legalHolds.reasonField')}</Label>
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
        {invalid ? t('legalHolds.reasonMissing') : t('legalHolds.reasonHint', { max: MAX_REASON })}
      </p>
    </div>
  )
}

/** The documents a search would put under a hold, for the records manager to review. */
export function SearchPreview({ preview }: { preview: HoldSearchPreview }) {
  const documents = preview.documents ?? []
  const outside = documents.some((d) => !d.manageable)
  return (
    <div className="grid gap-2" data-testid="hold-search-preview">
      <p className="text-sm" role="status">
        {documents.length === 0
          ? t('legalHolds.search.none')
          : t('legalHolds.search.summary', { count: preview.total, documents: documents.length })}
      </p>
      {outside && (
        <p role="alert" className="flex gap-2 text-sm text-destructive">
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {t('legalHolds.search.notManageableText')}
        </p>
      )}
      {documents.length > 0 && (
        <div className="max-h-56 overflow-y-auto rounded-md border">
          <ul aria-label={t('legalHolds.search.previewCaption')} className="divide-y text-sm">
            {documents.map((d) => (
              <li key={d.documentId} className="flex flex-wrap items-center gap-2 px-3 py-1.5">
                <Link
                  to="/documents/$documentId"
                  params={{ documentId: d.documentId }}
                  target="_blank"
                  className="underline-offset-4 hover:underline"
                >
                  {d.title}
                </Link>
                {d.documentNumber && (
                  <span className="font-mono text-xs text-muted-foreground">
                    {d.documentNumber}
                  </span>
                )}
                {d.underLegalHold && (
                  <Badge variant="secondary">{t('legalHolds.search.held')}</Badge>
                )}
                {!d.manageable && (
                  <Badge variant="destructive">{t('legalHolds.search.notManageable')}</Badge>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

/** An action's error; a changed search result is explained in the words of the dialog. */
export function HoldError({ error }: { error: unknown }) {
  if (isResultChanged(error)) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {t('legalHolds.search.resultChanged')}
      </p>
    )
  }
  return <InlineError error={error} />
}
