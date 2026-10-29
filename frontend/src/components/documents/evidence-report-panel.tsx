import { useMutation } from '@tanstack/react-query'
import { FileCheck2 } from 'lucide-react'
import { useApiClient } from '@/api/context'
import {
  createEvidenceReport,
  downloadEvidenceReport,
  type EvidenceReport,
  type EvidenceReportFormat,
} from '@/api/evidence-reports'
import { hasRole, useMe } from '@/api/me'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { t, tOptional } from '@/i18n'
import { formatDateTime } from '@/i18n/format'

/**
 * "Evidence report" for internal audit and records managers (DMS-57): creates the report of the
 * document, downloads it as PDF/A and shows its integrity status and checksum, with the JSON form
 * (the bytes the checksum is taken over) to download as well. Hidden for other roles; the backend
 * decides per document.
 */
export function EvidenceReportAction({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const me = useMe()
  const download = useMutation({
    mutationFn: ({ report, format }: { report: EvidenceReport; format: EvidenceReportFormat }) =>
      downloadEvidenceReport(client, report, format),
  })
  const create = useMutation({
    mutationFn: () => createEvidenceReport(client, documentId),
    onSuccess: (report) => {
      download.mutate({ report, format: 'pdf' })
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
              {tOptional(`docPanels.evidence.status.${report.integrityStatus}`) ??
                report.integrityStatus}
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
      {error && (
        <p role="alert" className="text-destructive">
          {error.message}
        </p>
      )}
    </div>
  )
}
