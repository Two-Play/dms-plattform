import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentVersionsQuery } from '@/api/versions'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { UserName } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDate } from '@/i18n/format'
import { StatusBadge } from './status-badge'
import { changeKindLabel } from './version-labels'

/**
 * The versions of a document the caller may read (US-05.1, DMS-54), newest first, with the authors' change
 * summaries. Two picked versions, or a version and its predecessor, open the comparison.
 */
export function VersionHistory({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const versions = useQuery(documentVersionsQuery(client, documentId))
  // At most two picked versions; picking a third drops the one picked first.
  const [picked, setPicked] = useState<string[]>([])

  const all = versions.data ?? []
  const position = new Map(all.map((v, i) => [v.id, i]))
  const newestFirst = [...all].reverse()

  function toggle(id: string) {
    setPicked((current) =>
      current.includes(id) ? current.filter((p) => p !== id) : [...current, id].slice(-2),
    )
  }

  function compare() {
    // Older version first, whatever order they were picked in.
    const [from, to] = [...picked].sort((a, b) => (position.get(a) ?? 0) - (position.get(b) ?? 0))
    if (from && to) {
      void navigate({
        to: '/documents/$documentId/compare',
        params: { documentId },
        search: { from, to },
      })
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('docPanels.history.title')}</CardTitle>
        <CardDescription>{t('docPanels.history.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {versions.isPending && <LoadingState label={t('docPanels.history.loading')} rows={2} />}
        {versions.isError && (
          <ErrorState error={versions.error} onRetry={() => void versions.refetch()} />
        )}
        {versions.data && (
          <>
            <ul aria-label={t('docPanels.history.list')} className="divide-y rounded-md border">
              {newestFirst.map((version) => {
                const index = position.get(version.id) ?? 0
                const previous = index > 0 ? all[index - 1] : undefined
                return (
                  <li key={version.id} className="flex flex-wrap items-start gap-3 p-3 text-sm">
                    <input
                      type="checkbox"
                      id={`pick-${version.id}`}
                      className="mt-1 size-4"
                      checked={picked.includes(version.id)}
                      onChange={() => {
                        toggle(version.id)
                      }}
                    />
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <label htmlFor={`pick-${version.id}`} className="font-medium">
                          {t('docPanels.history.version', { label: version.version })}
                        </label>
                        <StatusBadge status={version.status} />
                        <span className="text-muted-foreground">
                          {changeKindLabel(version.changeKind)} · {formatDate(version.createdAt)} ·{' '}
                          {t('docPanels.history.by')} <UserName id={version.createdBy} />
                        </span>
                      </div>
                      {version.changeReason && (
                        <p className="text-muted-foreground">{version.changeReason}</p>
                      )}
                    </div>
                    {previous && (
                      <Link
                        to="/documents/$documentId/compare"
                        params={{ documentId }}
                        search={{ from: previous.id, to: version.id }}
                        className="text-sm text-primary underline-offset-4 hover:underline"
                        aria-label={t('docPanels.history.changesSinceLabel', {
                          version: version.version,
                          previous: previous.version,
                        })}
                      >
                        {t('docPanels.history.changesSince', { previous: previous.version })}
                      </Link>
                    )}
                  </li>
                )
              })}
            </ul>
            <Button type="button" disabled={picked.length !== 2} onClick={compare}>
              {t('docPanels.history.compare')}
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  )
}
