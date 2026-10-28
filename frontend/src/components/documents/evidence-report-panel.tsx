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
import { Button } from '@/components/ui/button'

const dateTimeFormat = new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short' })

const statusText: Record<string, string> = {
  Intact: 'Integrity intact',
  Violated: 'Integrity violated',
  Incomplete: 'Integrity not fully checked',
}

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
        {create.isPending ? 'Creating evidence report…' : 'Evidence report'}
      </Button>
      {report && (
        <div className="space-y-1 rounded-md border p-3">
          <p>
            <span
              className={
                report.integrityStatus === 'Intact' ? 'font-medium' : 'font-medium text-destructive'
              }
            >
              {statusText[report.integrityStatus] ?? report.integrityStatus}
            </span>{' '}
            · created {dateTimeFormat.format(new Date(report.createdAt))} by {report.createdBy}
          </p>
          <p className="break-all text-muted-foreground">SHA-256 {report.sha256}</p>
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
              Download PDF
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={download.isPending}
              onClick={() => {
                download.mutate({ report, format: 'json' })
              }}
            >
              Download JSON
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
