import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { RotateCw } from 'lucide-react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import {
  invalidatePublication,
  publicationErrorCode,
  publicationFailuresQuery,
  publicationStatusQuery,
  retryPublication,
  type PublicationError,
} from '@/api/publications'
import { ErrorState } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { errorText } from '@/components/shared/error-messages'

function publicationCause(error: PublicationError | undefined): string {
  const code = publicationErrorCode(error)
  return code ? t(`publications.causes.${code}`) : t('publications.causes.unknown')
}

function retryFailure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('publications.retryForbidden')
    if (error.status === 409) return t('publications.retryConflict', { message: errorText(error) })
  }
  return t('publications.retryFailed', { message: errorText(error) })
}

/** Requeues a failed publication; the caller's permission is decided by the backend (`canRetry`). */
function RetryPublicationButton({
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
    mutationFn: () => retryPublication(client, documentId, versionId),
    onSuccess: async () => {
      toast(t('publications.retried', { version }))
      await invalidatePublication(queryClient, documentId)
    },
    onError: (error) => {
      toast(retryFailure(error), 'error')
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
      {retry.isPending ? t('publications.retryPending') : t('publications.retry')}
    </Button>
  )
}

/**
 * The failed publication of an approved version on the document page (DMS-142): the cause and, for
 * the document's owner, records managers and tenant administrators, a retry button. A publication
 * that failed for good also names the way out if a retry cannot help: withdrawing the version
 * (DMS-145), which `canWithdraw` callers find in the page's actions. Shows nothing while the
 * publication is simply waiting for its date.
 */
export function PublicationStatusNotice({
  documentId,
  versionId,
  canWithdraw = false,
}: {
  documentId: string
  versionId: string
  canWithdraw?: boolean
}) {
  const client = useApiClient()
  const status = useQuery(publicationStatusQuery(client, documentId, versionId))
  const s = status.data
  if (!s || (s.state !== 'Failed' && s.state !== 'Retrying')) return null
  return (
    <div
      role={s.state === 'Failed' ? 'alert' : 'status'}
      data-testid="publication-status"
      className="space-y-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm"
    >
      <p>
        {s.state === 'Failed'
          ? t('publications.failed', { max: s.maxAttempts })
          : t('publications.retrying', {
              attempt: s.attempts,
              max: s.maxAttempts,
              date: s.nextAttemptAt ? formatDateTime(s.nextAttemptAt) : '–',
            })}
      </p>
      <p>
        <strong>{t('publications.cause')}</strong> {publicationCause(s.lastError)}
      </p>
      {s.lastError && (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer">{t('publications.technicalDetail')}</summary>
          <p className="mt-1 break-words font-mono">{s.lastError.message}</p>
        </details>
      )}
      {s.state === 'Failed' && (
        <p>{canWithdraw ? t('publications.withdrawHint') : t('publications.withdrawHintOthers')}</p>
      )}
      {s.canRetry && (
        <RetryPublicationButton documentId={documentId} versionId={versionId} version={s.version} />
      )}
    </div>
  )
}

/**
 * Approved versions whose publication failed or is being retried (DMS-142): those the caller's role
 * covers or they are named for (ADR 0064), each with its cause and a retry button where the caller
 * may retry. Titles of documents the caller is not cleared for are not shown.
 */
export function PublicationFailuresPanel() {
  const client = useApiClient()
  const failures = useQuery(publicationFailuresQuery(client))
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('publications.failures.title')}</h2>
        </CardTitle>
        <CardDescription>{t('publications.failures.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {failures.isPending && <LoadingState label={t('publications.failures.loading')} />}
        {failures.isError && (
          <ErrorState error={failures.error} onRetry={() => void failures.refetch()} />
        )}
        {failures.data?.length === 0 && <EmptyState title={t('publications.failures.empty')} />}
        {failures.data && failures.data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">{t('publications.failures.caption')}</caption>
              <thead className="text-left text-xs text-muted-foreground">
                <tr className="border-b">
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('publications.failures.document')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('publications.failures.version')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('publications.failures.state')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('publications.failures.cause')}
                  </th>
                  <th scope="col" className="py-1.5 font-medium">
                    {t('publications.failures.action')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {failures.data.map((f) => (
                  <tr key={f.versionId} className="border-b align-top last:border-0">
                    <td className="py-1.5 pr-3">
                      {f.title ? (
                        <Link
                          to="/documents/$documentId"
                          params={{ documentId: f.documentId }}
                          search={{ version: f.versionId }}
                          className="underline-offset-4 hover:underline"
                        >
                          {f.title}
                        </Link>
                      ) : (
                        <span className="text-muted-foreground">
                          {t('publications.failures.confidential')}
                        </span>
                      )}
                    </td>
                    <td className="py-1.5 pr-3">{f.version}</td>
                    <td className="py-1.5 pr-3">
                      {f.state === 'Failed'
                        ? t('publications.failures.failed')
                        : t('publications.failures.retrying')}
                      <div className="text-xs text-muted-foreground">
                        {f.state === 'Retrying' && f.nextAttemptAt
                          ? t('publications.failures.nextAttempt', {
                              date: formatDateTime(f.nextAttemptAt),
                            })
                          : f.failedAt
                            ? formatDateTime(f.failedAt)
                            : null}
                      </div>
                    </td>
                    <td className="py-1.5 pr-3">
                      {publicationCause(f.lastError)}
                      {f.lastError && (
                        <details className="text-xs text-muted-foreground">
                          <summary className="cursor-pointer">
                            {t('publications.technicalDetail')}
                          </summary>
                          <p className="mt-1 break-words font-mono">{f.lastError.message}</p>
                        </details>
                      )}
                    </td>
                    <td className="py-1.5">
                      {f.canRetry && (
                        <RetryPublicationButton
                          documentId={f.documentId}
                          versionId={f.versionId}
                          version={f.version}
                        />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
