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
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function StatusCounts({ counts, caption }: { counts: StatusCount[]; caption: string }) {
  return (
    <table className="w-full text-sm">
      <caption className="sr-only">{caption}</caption>
      <thead className="text-left text-xs text-muted-foreground">
        <tr className="border-b">
          <th scope="col" className="py-1 pr-4 font-medium">
            Document status
          </th>
          <th scope="col" className="py-1 pr-4 text-right font-medium">
            Documents
          </th>
          <th scope="col" className="py-1 text-right font-medium">
            Versions
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
          {impact.documents === 1
            ? '1 document uses an earlier version'
            : `${String(impact.documents)} documents use an earlier version`}
        </h3>
        {byStatus.length > 0 && (
          <StatusCounts counts={byStatus} caption="Documents on earlier versions by status" />
        )}
        {(impact.byVersion ?? []).length > 1 && (
          <ul className="space-y-1 text-xs text-muted-foreground">
            {(impact.byVersion ?? []).map((v) => (
              <li key={v.templateVersion}>
                Version {v.templateVersion}: {v.documents}{' '}
                {v.documents === 1 ? 'document' : 'documents'}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="impact-drafts" className="space-y-2">
        <h3 id="impact-drafts" className="font-medium">
          Open drafts on earlier versions ({drafts.length})
        </h3>
        {drafts.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nobody is working on such a document.</p>
        ) : (
          <>
            <div className="max-h-56 overflow-auto">
              <table className="w-full text-sm">
                <caption className="sr-only">Open drafts on earlier versions</caption>
                <thead className="text-left text-xs text-muted-foreground">
                  <tr className="border-b">
                    <th scope="col" className="py-1 pr-3 font-medium">
                      Title
                    </th>
                    <th scope="col" className="py-1 pr-3 font-medium">
                      Author
                    </th>
                    <th scope="col" className="py-1 pr-3 font-medium">
                      Status
                    </th>
                    <th scope="col" className="hidden py-1 font-medium sm:table-cell">
                      Changed
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
                          {d.version} · template version {d.templateVersion}
                        </span>
                      </td>
                      <td className="py-1 pr-3 font-mono text-xs">{d.author}</td>
                      <td className="py-1 pr-3">
                        <StatusBadge status={d.status} />
                      </td>
                      <td className="hidden py-1 text-muted-foreground sm:table-cell">
                        {dateTime.format(new Date(d.updatedAt))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted-foreground">
              Authors are shown by user id. Titles of confidential documents you may not read are
              hidden.
            </p>
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
            Release {version.name}, version {version.version}
          </DialogTitle>
          <DialogDescription>
            Check how documents use the earlier versions before you release this one.
          </DialogDescription>
        </DialogHeader>
        {impact.isPending && <Skeleton className="h-32 w-full" />}
        {impact.isError && (
          <p role="alert" className="text-sm text-destructive">
            {impact.error.message}
          </p>
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
            Cancel
          </Button>
          <Button onClick={onRelease} disabled={!impact.data || releasing}>
            {releasing ? 'Releasing…' : `Release version ${String(version.version)}`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
