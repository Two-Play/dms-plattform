import { AlertCircle, RotateCw } from 'lucide-react'
import { cn } from 'cn'
import { Button } from '@/components/ui/button'
import {
  errorDetail,
  errorKind,
  errorText,
  errorTexts,
  isRetryable,
  type ErrorKind,
} from './error-messages'

const texts = {
  retry: 'Try again',
}

/**
 * A failed load with a user-facing explanation, the backend's message if it sent one and "Try
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
  const text = errorTexts[kind]
  const title = titles?.[kind] ?? text.title
  const detail = errorDetail(error)
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
        <p className="text-muted-foreground">{text.description}</p>
        {detail && detail !== title && (
          <p className="text-xs break-words text-muted-foreground">{detail}</p>
        )}
        {onRetry && isRetryable(kind) && (
          <Button size="sm" variant="outline" className="mt-2" onClick={onRetry}>
            <RotateCw aria-hidden="true" />
            {texts.retry}
          </Button>
        )}
      </div>
    </div>
  )
}

/** The error of an action (save, vote, ...) as one line next to the control that failed. */
export function InlineError({ error, className }: { error: unknown; className?: string }) {
  return (
    <p role="alert" className={cn('text-sm text-destructive', className)}>
      {errorText(error)}
    </p>
  )
}
