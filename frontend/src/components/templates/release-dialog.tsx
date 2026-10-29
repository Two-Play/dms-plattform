import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { useQuery } from '@tanstack/react-query'
import { Lock } from 'lucide-react'
import { useApiClient } from '@/api/context'
import {
  templateImpactQuery,
  type StatusCount,
  type TemplateImpact,
  type TemplateVersion,
} from '@/api/template-admin'
import { StatusBadge } from '@/components/documents/status-badge'
import { statusLabel } from '@/components/documents/status-label'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

function StatusCounts({ counts, caption }: { counts: StatusCount[]; caption: string }) {
  return (
    <table className="w-full text-sm">
      <caption className="sr-only">{caption}</caption>
      <thead className="text-left text-xs text-muted-foreground">
        <tr className="border-b">
          <th scope="col" className="py-1 pr-4 font-medium">
            {t('templates.release.documentStatus')}
          </th>
          <th scope="col" className="py-1 pr-4 text-right font-medium">
            {t('templates.release.documents')}
          </th>
          <th scope="col" className="py-1 text-right font-medium">
            {t('templates.release.versions')}
          </th>
        </tr>
      </thead>
      <tbody>
        {counts.map((c) => (
          <tr key={c.status} className="border-b last:border-0">
            <td className="py-1 pr-4">{statusLabel(c.status)}</td>
            <td className="py-1 pr-4 text-right tabular-nums">{c.documents}</td>
            <td className="py-1 text-right tabular-nums">{c.versions}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Impact({ impact }: { impact: TemplateImpact }) {
  const drafts = impact.openDrafts ?? []
  const byStatus = impact.byStatus ?? []
  return (
    <div className="space-y-4">
      <p className="rounded-lg border bg-muted/50 p-3 text-sm">{impact.explanation}</p>
      <section aria-labelledby="impact-documents" className="space-y-2">
        <h3 id="impact-documents" className="font-medium">
          {t('templates.release.usage', { count: impact.documents })}
        </h3>
        {byStatus.length > 0 && (
          <StatusCounts counts={byStatus} caption={t('templates.release.byStatus')} />
        )}
        {(impact.byVersion ?? []).length > 1 && (
          <ul className="space-y-1 text-xs text-muted-foreground">
            {(impact.byVersion ?? []).map((v) => (
              <li key={v.templateVersion}>
                {t('templates.release.perVersion', {
                  version: v.templateVersion,
                  count: v.documents,
                })}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="impact-drafts" className="space-y-2">
        <h3 id="impact-drafts" className="font-medium">
          {t('templates.release.drafts', { count: drafts.length })}
        </h3>
        {drafts.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('templates.release.noDrafts')}</p>
        ) : (
          <>
            <div className="max-h-56 overflow-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">{t('templates.release.draftsCaption')}</caption>
                <thead className="text-left text-xs text-muted-foreground">
                  <tr className="border-b">
                    <th scope="col" className="py-1 pr-3 font-medium">
                      {t('templates.release.title')}
                    </th>
                    <th scope="col" className="py-1 pr-3 font-medium">
                      {t('templates.release.author')}
                    </th>
                    <th scope="col" className="py-1 pr-3 font-medium">
                      {t('templates.release.status')}
                    </th>
                    <th scope="col" className="hidden py-1 font-medium sm:table-cell">
                      {t('templates.release.changed')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {drafts.map((d) => (
                    <tr key={d.versionId} className="border-b last:border-0">
                      <td className="py-1 pr-3">
                        {d.titleMasked ? (
                          <span className="flex items-center gap-1 text-muted-foreground italic">
                            <Lock className="size-3" aria-hidden="true" />
                            {d.title}
                          </span>
                        ) : (
                          d.title
                        )}
                        <span className="block font-mono text-xs text-muted-foreground">
                          {t('templates.release.draftVersion', {
                            version: d.version,
                            templateVersion: d.templateVersion,
                          })}
                        </span>
                      </td>
                      <td className="py-1 pr-3">
                        <UserName id={d.author} />
                      </td>
                      <td className="py-1 pr-3">
                        <StatusBadge status={d.status} />
                      </td>
                      <td className="hidden py-1 text-muted-foreground sm:table-cell">
                        {formatDateTime(d.updatedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted-foreground">{t('templates.release.maskedNote')}</p>
          </>
        )}
      </section>
    </div>
  )
}

interface ReleaseDialogProps {
  version: TemplateVersion
  open: boolean
  onOpenChange: (open: boolean) => void
  onRelease: () => void
  releasing: boolean
  /** Message of a failed release, shown in the dialog. */
  error: string | undefined
}

/**
 * Release of a version in testing (four-eyes principle): shows the impact analysis first (US-01.2), then
 * offers the release. The analysis is informational; releasing changes no existing document.
 */
export function ReleaseDialog({
  version,
  open,
  onOpenChange,
  onRelease,
  releasing,
  error,
}: ReleaseDialogProps) {
  const client = useApiClient()
  const impact = useQuery({
    ...templateImpactQuery(client, version.templateId, version.version),
    enabled: open,
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {t('templates.release.dialogTitle', { name: version.name, version: version.version })}
          </DialogTitle>
          <DialogDescription>{t('templates.release.dialogText')}</DialogDescription>
        </DialogHeader>
        {impact.isPending && <LoadingState label={t('templates.release.analysing')} rows={3} />}
        {impact.isError && (
          <ErrorState error={impact.error} onRetry={() => void impact.refetch()} />
        )}
        {impact.data && <Impact impact={impact.data} />}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => {
              onOpenChange(false)
            }}
          >
            {t('common.cancel')}
          </Button>
          <Button onClick={onRelease} disabled={!impact.data || releasing}>
            {releasing
              ? t('templates.release.releasing')
              : t('templates.release.release', { version: version.version })}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
