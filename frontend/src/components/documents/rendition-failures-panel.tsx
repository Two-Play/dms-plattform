import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useApiClient } from '@/api/context'
import { renditionFailuresQuery, type RenditionState } from '@/api/renditions'
import { ErrorState } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { renditionCause } from './rendition-messages'
import { RetryRenditionButton } from './rendition-status'

const STATES: readonly RenditionState[] = ['Failed', 'Retrying', 'Pending', 'Done', 'None']

function stateLabel(state: string): string {
  return (STATES as readonly string[]).includes(state)
    ? t(`renditions.failures.states.${state as RenditionState}`)
    : state
}

/**
 * Versions of the tenant whose archive PDF failed or is being retried (DMS-93), for records
 * managers and tenant administrators, each with its cause and a retry button where the caller may
 * retry. Titles of documents the caller is not cleared for are not shown.
 */
export function RenditionFailuresPanel() {
  const client = useApiClient()
  const failures = useQuery(renditionFailuresQuery(client))
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('renditions.failures.title')}</h2>
        </CardTitle>
        <CardDescription>{t('renditions.failures.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {failures.isPending && <LoadingState label={t('renditions.failures.loading')} />}
        {failures.isError && (
          <ErrorState error={failures.error} onRetry={() => void failures.refetch()} />
        )}
        {failures.data?.length === 0 && <EmptyState title={t('renditions.failures.empty')} />}
        {failures.data && failures.data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">{t('renditions.failures.caption')}</caption>
              <thead className="text-left text-xs text-muted-foreground">
                <tr className="border-b">
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('renditions.failures.document')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('renditions.failures.version')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('renditions.failures.state')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('renditions.failures.attempts')}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t('renditions.failures.cause')}
                  </th>
                  <th scope="col" className="py-1.5 font-medium">
                    {t('renditions.failures.action')}
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
                          className="underline-offset-4 hover:underline"
                        >
                          {f.title}
                        </Link>
                      ) : (
                        <span className="text-muted-foreground">
                          {t('renditions.failures.confidential')}
                        </span>
                      )}
                    </td>
                    <td className="py-1.5 pr-3">{f.version}</td>
                    <td className="py-1.5 pr-3">
                      {stateLabel(f.state)}
                      <div className="text-xs text-muted-foreground">
                        {f.state === 'Retrying' && f.nextAttemptAt
                          ? t('renditions.failures.nextAttempt', {
                              date: formatDateTime(f.nextAttemptAt),
                            })
                          : f.failedAt
                            ? formatDateTime(f.failedAt)
                            : null}
                      </div>
                    </td>
                    <td className="py-1.5 pr-3">
                      {f.attempts} / {f.maxAttempts}
                    </td>
                    <td className="py-1.5 pr-3">
                      {renditionCause(f.lastError)}
                      {f.lastError && (
                        <div className="break-words font-mono text-xs text-muted-foreground">
                          {f.lastError.message}
                        </div>
                      )}
                    </td>
                    <td className="py-1.5">
                      {f.canRetry && (
                        <RetryRenditionButton
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
