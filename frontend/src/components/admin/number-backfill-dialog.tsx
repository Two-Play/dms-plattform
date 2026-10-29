import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApiClient } from '@/api/context'
import {
  previewNumberBackfill,
  runNumberBackfill,
  type ClassNumberRange,
  type NumberBackfillResult,
} from '@/api/number-ranges'
import { InlineError } from '@/components/shared/error-state'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/** Why the backend skipped a document (`NumberBackfillEntry.reason`). */
function skipReason(code?: string): string {
  switch (code) {
    case 'has-number':
      return t('numberRanges.skip.hasNumber')
    case 'no-range':
      return t('numberRanges.skip.noRange')
    case 'in-review':
      return t('numberRanges.skip.inReview')
    case 'unit-missing':
      return t('numberRanges.skip.unitMissing')
    default:
      return code ?? ''
  }
}

/** Counts per reason, e.g. "in review (2)". */
function grouped(reasons: string[]): string[] {
  const counts = new Map<string, number>()
  for (const r of reasons) counts.set(r, (counts.get(r) ?? 0) + 1)
  return [...counts].map(([reason, count]) => (count > 1 ? `${reason} (${String(count)})` : reason))
}

function Summary({ result }: { result: NumberBackfillResult }) {
  const entries = result.entries ?? []
  const numbers = entries.flatMap((e) => (e.number ? [e.number] : []))
  const skipped = grouped(
    entries.filter((e) => e.outcome === 'Skipped').map((e) => skipReason(e.reason)),
  )
  const failed = grouped(entries.filter((e) => e.outcome === 'Failed').map((e) => e.reason ?? ''))
  return (
    <div role="status" className="space-y-2 text-sm" data-testid="backfill-result">
      <p className="font-medium">{t('numberRanges.backfillDone', { count: result.numbered })}</p>
      {numbers.length > 0 && (
        <p className="font-mono text-xs">
          {t('numberRanges.backfillRange', {
            first: numbers[0] ?? '',
            last: numbers[numbers.length - 1] ?? '',
          })}
        </p>
      )}
      {result.skipped > 0 && (
        <div>
          <p>{t('numberRanges.backfillSkipped', { count: result.skipped })}</p>
          <ul className="list-disc pl-5 text-muted-foreground">
            {skipped.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </div>
      )}
      {result.failed > 0 && (
        <div className="text-destructive">
          <p>{t('numberRanges.backfillFailed', { count: result.failed })}</p>
          <ul className="list-disc pl-5">
            {failed.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
        </div>
      )}
      {result.remaining > 0 && (
        <p>{t('numberRanges.backfillRemaining', { count: result.remaining })}</p>
      )}
    </div>
  )
}

function BackfillContent({
  cls,
  rangeName,
  onClose,
}: {
  cls: ClassNumberRange
  rangeName: string
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const preview = useQuery({
    queryKey: ['number-ranges', 'backfill', cls.classId],
    queryFn: () => previewNumberBackfill(client, cls.classId),
  })
  const run = useMutation({
    mutationFn: () => runNumberBackfill(client, cls.classId),
    onSuccess: async () => {
      toast(t('numberRanges.backfillToast', { cls: cls.label }))
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['number-ranges'] }),
        queryClient.invalidateQueries({ queryKey: ['documents'] }),
      ])
    },
  })
  const count = preview.data?.documents ?? 0
  return (
    <>
      <DialogHeader>
        <DialogTitle>{t('numberRanges.backfillTitle', { cls: cls.label })}</DialogTitle>
        <DialogDescription>
          {t('numberRanges.backfillDescription', { range: rangeName })}
        </DialogDescription>
      </DialogHeader>
      {preview.isPending && (
        <p className="text-sm text-muted-foreground">{t('numberRanges.backfillLoading')}</p>
      )}
      {preview.isError && <InlineError error={preview.error} />}
      {preview.data && !run.data && (
        <div className="space-y-1 text-sm" data-testid="backfill-preview">
          <p className="font-medium">{t('numberRanges.backfillCount', { count })}</p>
          {count > preview.data.maxPerRun && (
            <p>{t('numberRanges.backfillLimit', { max: preview.data.maxPerRun })}</p>
          )}
          <p className="text-muted-foreground">
            {t('numberRanges.backfillNext', { number: preview.data.nextNumber })}
          </p>
        </div>
      )}
      {run.data && <Summary result={run.data} />}
      {run.isError && <InlineError error={run.error} />}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          {run.data ? t('numberRanges.backfillClose') : t('common.cancel')}
        </Button>
        {!run.data && count > 0 && (
          <Button
            type="button"
            disabled={run.isPending}
            onClick={() => {
              run.mutate()
            }}
          >
            {run.isPending
              ? t('numberRanges.backfillRunning')
              : t('numberRanges.backfillConfirm', {
                  count: Math.min(count, preview.data?.maxPerRun ?? count),
                })}
          </Button>
        )}
      </DialogFooter>
    </>
  )
}

/**
 * Confirmation and result of numbering the existing documents of a class (DMS-116): shows how many
 * documents have no number, runs the backfill on confirmation and summarizes what happened.
 */
export function NumberBackfillDialog({
  cls,
  rangeName,
  onClose,
}: {
  cls?: ClassNumberRange
  rangeName: string
  onClose: () => void
}) {
  return (
    <Dialog
      open={cls !== undefined}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        {cls && (
          <BackfillContent key={cls.classId} cls={cls} rangeName={rangeName} onClose={onClose} />
        )}
      </DialogContent>
    </Dialog>
  )
}
