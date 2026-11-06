import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
import { useApiClient } from '@/api/context'
import {
  createEvidenceReport,
  downloadEvidenceReport,
  evidenceReportsKey,
  evidenceReportsQuery,
  type EvidenceReport,
  type EvidenceReportEntry,
  type EvidenceReportFormat,
} from '@/api/evidence-reports'
import { hasRole, useMe } from '@/api/me'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { t, tOptional } from '@/i18n'
import { formatDateTime } from '@/i18n/format'

function statusText(status: string): string {
  return tOptional(`docPanels.evidence.status.${status}`) ?? status
}

/** One earlier report: when, by whom, what it found, and its files as long as they exist. */
function EarlierReport({ report }: { report: EvidenceReportEntry }) {
  const client = useApiClient()
  const download = useMutation({
    mutationFn: (format: EvidenceReportFormat) => downloadEvidenceReport(client, report, format),
  })
  const date = formatDateTime(report.createdAt)
  return (
    <li className="space-y-1 rounded-md border p-2">
      <p>
        {report.integrityStatus !== undefined && (
          <span
            className={
              report.integrityStatus === 'Intact' ? 'font-medium' : 'font-medium text-destructive'
            }
          >
            {statusText(report.integrityStatus)}
          </span>
        )}{' '}
        {t('docPanels.evidence.created', { date })} <UserName id={report.createdBy} />
      </p>
      <p className="break-all text-xs text-muted-foreground">
        {t('docPanels.evidence.checksum', { sha: report.sha256 })}
      </p>
      {report.removedAt !== undefined ? (
        <p className="text-muted-foreground">
          {t('docPanels.evidence.removed', { date: formatDateTime(report.removedAt) })}
        </p>
      ) : (
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="ghost"
            disabled={download.isPending}
            aria-label={t('docPanels.evidence.downloadPdfOf', { date })}
            onClick={() => {
              download.mutate('pdf')
            }}
          >
            {t('docPanels.evidence.downloadPdf')}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={download.isPending}
            aria-label={t('docPanels.evidence.downloadJsonOf', { date })}
            onClick={() => {
              download.mutate('json')
            }}
          >
            {t('docPanels.evidence.downloadJson')}
          </Button>
        </div>
      )}
      {download.isError && <InlineError error={download.error} />}
    </li>
  )
}

/** The reports created so far (ADR 0077). Nothing is shown to a caller the backend refuses. */
function EarlierReports({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const reports = useInfiniteQuery(evidenceReportsQuery(client, documentId))

  if (reports.isPending) return <LoadingState label={t('docPanels.evidence.loading')} rows={1} />
  if (reports.isLoadingError) {
    return <ErrorState error={reports.error} onRetry={() => void reports.refetch()} />
  }
  const pages = reports.data.pages
  if (pages.some((page) => page === null)) return null
  const items = pages.flatMap((page) => page?.items ?? [])
  return (
    <section aria-label={t('docPanels.evidence.earlier')} className="space-y-2">
      <h3 className="font-medium">{t('docPanels.evidence.earlier')}</h3>
      {items.length === 0 ? (
        <p className="text-muted-foreground">{t('docPanels.evidence.none')}</p>
      ) : (
        <ul className="space-y-2">
          {items.map((report) => (
            <EarlierReport key={report.id} report={report} />
          ))}
        </ul>
      )}
      {reports.hasNextPage && (
        <Button
          size="sm"
          variant="outline"
          disabled={reports.isFetchingNextPage}
          onClick={() => {
            void reports.fetchNextPage()
          }}
        >
          {reports.isFetchingNextPage ? t('common.loading') : t('docPanels.evidence.more')}
        </Button>
      )}
      {reports.isFetchNextPageError && <InlineError error={reports.error} />}
    </section>
  )
}

/**
 * "Evidence report" for internal audit and records managers (DMS-57): creates the report of the
 * document, downloads it as PDF/A and shows its integrity status and checksum, with the JSON form
 * (the bytes the checksum is taken over) to download as well, and lists the earlier reports. Hidden
 * for other roles; the backend decides per document.
 */
export function EvidenceReportAction({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const me = useMe()
  const download = useMutation({
    mutationFn: ({ report, format }: { report: EvidenceReport; format: EvidenceReportFormat }) =>
      downloadEvidenceReport(client, report, format),
  })
  const create = useMutation({
    mutationFn: () => createEvidenceReport(client, documentId),
    onSuccess: (report) => {
      download.mutate({ report, format: 'pdf' })
      void queryClient.invalidateQueries({ queryKey: evidenceReportsKey(documentId) })
    },
  })

  if (!hasRole(me.data, 'Auditor') && !hasRole(me.data, 'RecordsManager')) return null

  const report = create.data
  const error = create.error ?? download.error
  return (
    <div className="space-y-2 text-sm" data-testid="evidence-report">
      <Button
        size="sm"
        variant="outline"
        disabled={create.isPending}
        onClick={() => {
          create.mutate()
        }}
      >
        <FileCheck2 className="size-4" aria-hidden />
        {create.isPending ? t('docPanels.evidence.creating') : t('docPanels.evidence.create')}
      </Button>
      {report && (
        <div className="space-y-1 rounded-md border p-3">
          <p>
            <span
              className={
                report.integrityStatus === 'Intact' ? 'font-medium' : 'font-medium text-destructive'
              }
            >
              {statusText(report.integrityStatus)}
            </span>{' '}
            {t('docPanels.evidence.created', { date: formatDateTime(report.createdAt) })}{' '}
            <UserName id={report.createdBy} />
          </p>
          <p className="break-all text-muted-foreground">
            {t('docPanels.evidence.checksum', { sha: report.sha256 })}
          </p>
          {report.findings && report.findings.length > 0 && (
            <ul className="list-disc pl-5 text-muted-foreground">
              {report.findings.map((finding) => (
                <li key={finding}>{finding}</li>
              ))}
            </ul>
          )}
          <div className="flex gap-2 pt-1">
            <Button
              size="sm"
              variant="ghost"
              disabled={download.isPending}
              onClick={() => {
                download.mutate({ report, format: 'pdf' })
              }}
            >
              {t('docPanels.evidence.downloadPdf')}
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={download.isPending}
              onClick={() => {
                download.mutate({ report, format: 'json' })
              }}
            >
              {t('docPanels.evidence.downloadJson')}
            </Button>
          </div>
        </div>
      )}
      {error && <InlineError error={error} />}
      <EarlierReports documentId={documentId} />
    </div>
  )
}
