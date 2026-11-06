import { AlertCircle, RotateCw } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import {
  codeText,
  errorDetail,
  errorKind,
  errorText,
  errorTexts,
  isRetryable,
  type ErrorKind,
} from './error-messages'
import { t } from '@/i18n'

/** What the backend said about an error, collapsed: it is English and meant for support. */
export function TechnicalDetail({ error, className }: { error: unknown; className?: string }) {
  const detail = errorDetail(error)
  if (!detail) return null
  return (
    <details className={cn('text-xs text-muted-foreground', className)}>
      <summary className="w-fit cursor-pointer rounded-sm">{t('errors.technicalDetails')}</summary>
      <p className="mt-1 break-words">{detail}</p>
    </details>
  )
}

/**
 * A failed load with a user-facing explanation, the backend's message as a technical detail and "Try
 * again" where retrying can help. `titles` replaces the heading per kind, e.g. "This document does
 * not exist." for `notFound`.
 */
export function ErrorState({
  error,
  onRetry,
  titles,
  className,
}: {
  error: unknown
  onRetry?: () => void
  titles?: Partial<Record<ErrorKind, string>>
  className?: string
}) {
  const kind = errorKind(error)
  const text = errorTexts(kind)
  const title = titles?.[kind] ?? text.title
  return (
    <div
      role="alert"
      className={cn(
        'flex gap-3 rounded-lg border border-destructive/40 bg-destructive/5 p-4 text-sm',
        className,
      )}
    >
      <AlertCircle aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-destructive" />
      <div className="min-w-0 space-y-1">
        <p className="font-medium">{title}</p>
        <p className="text-muted-foreground">{codeText(error) ?? text.description}</p>
        <TechnicalDetail error={error} />
        {onRetry && isRetryable(kind) && (
          <Button size="sm" variant="outline" className="mt-2" onClick={onRetry}>
            <RotateCw aria-hidden="true" />
            {t('common.retry')}
          </Button>
        )}
      </div>
    </div>
  )
}

/**
 * The error of an action (save, vote, ...) next to the control that failed: `text`, or the text for the
 * error, and the technical detail.
 */
export function InlineError({
  error,
  text,
  className,
}: {
  error: unknown
  text?: string
  className?: string
}) {
  return (
    <div role="alert" className={cn('space-y-1 text-sm text-destructive', className)}>
      <p>{text ?? errorText(error)}</p>
      <TechnicalDetail error={error} />
    </div>
  )
}
