import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RotateCw } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { invalidateRendition, renditionStatusQuery, retryRendition } from '@/api/renditions'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { renditionCause, retryFailure } from './rendition-messages'

/** Requeues a failed rendition; the caller's permission is decided by the backend (`canRetry`). */
export function RetryRenditionButton({
  documentId,
  versionId,
  version,
}: {
  documentId: string
  versionId: string
  version: string
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const retry = useMutation({
    mutationFn: () => retryRendition(client, documentId, versionId),
    onSuccess: async () => {
      toast(t('renditions.retried', { version }))
      await invalidateRendition(queryClient, documentId)
    },
    onError: (error) => {
      toast(retryFailure(error))
    },
  })
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={retry.isPending}
      onClick={() => {
        retry.mutate()
      }}
    >
      <RotateCw aria-hidden="true" />
      {retry.isPending ? t('renditions.retryPending') : t('renditions.retry')}
    </Button>
  )
}

/**
 * The state of the archive rendition of a version waiting for it (workflow case `Rendering`,
 * DMS-93): produced, retried automatically, or failed with its cause and, for the document's
 * owner, records managers and tenant administrators, a retry button.
 */
export function RenditionStatusNotice({
  documentId,
  versionId,
}: {
  documentId: string
  versionId: string
}) {
  const client = useApiClient()
  const status = useQuery(renditionStatusQuery(client, documentId, versionId))
  if (status.isPending) return <LoadingState label={t('renditions.loading')} rows={1} />
  if (status.isError)
    return <ErrorState error={status.error} onRetry={() => void status.refetch()} />
  const s = status.data
  const failed = s.state === 'Failed' || s.state === 'Retrying'
  const message =
    s.state === 'Failed'
      ? t('renditions.failed', { max: s.maxAttempts })
      : s.state === 'Retrying'
        ? t('renditions.retrying', {
            attempt: s.attempts,
            max: s.maxAttempts,
            date: s.nextAttemptAt ? formatDateTime(s.nextAttemptAt) : '–',
          })
        : s.state === 'Done'
          ? t('renditions.done')
          : s.state === 'None'
            ? t('renditions.none')
            : t('renditions.pending')
  return (
    <div
      role={s.state === 'Failed' ? 'alert' : 'status'}
      data-testid="rendition-status"
      className={
        failed
          ? 'space-y-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm'
          : 'text-sm text-muted-foreground'
      }
    >
      <p>{message}</p>
      {failed && (
        <>
          <p>
            <strong>{t('renditions.cause')}</strong> {renditionCause(s.lastError)}
          </p>
          {s.lastError && (
            <details className="text-xs text-muted-foreground">
              <summary className="cursor-pointer">{t('renditions.technicalDetail')}</summary>
              <p className="mt-1 break-words font-mono">{s.lastError.message}</p>
            </details>
          )}
          {s.canRetry && (
            <RetryRenditionButton
              documentId={documentId}
              versionId={versionId}
              version={s.version}
            />
          )}
        </>
      )}
    </div>
  )
}
