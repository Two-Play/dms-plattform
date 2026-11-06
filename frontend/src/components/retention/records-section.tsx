import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CheckCircle2, EyeOff } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import {
  correctRecord,
  draftsBelowBasisQuery,
  invalidateAfterCorrection,
  recordDifferencesQuery,
  type DraftBelowBasis,
  type RecordDifference,
} from '@/api/records'
import { statusLabel } from '@/components/documents/status-label'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { t, tOptional } from '@/i18n'
import { formatDate } from '@/i18n/format'
import { toast } from '@/lib/toast'
import { ReasonField } from './reason-field'

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4 align-top'

function level(code: string): string {
  return tOptional(`docs.meta.levels.${code}`) ?? code
}

/** Says that the list is not all there is: the rest needs someone cleared. */
function NotCleared({ count }: { count: number }) {
  return <>{t('retention.records.notCleared', { count })}</>
}

/** The note under a list. Nothing listed does not mean nothing is there if documents were left out. */
function ListEnd({
  rows,
  notCleared,
  none,
  noneText,
}: {
  rows: number
  notCleared: number
  none: string
  noneText: string
}) {
  if (rows === 0 && notCleared > 0) {
    return (
      <EmptyState
        icon={EyeOff}
        title={t('retention.records.incomplete')}
        description={<NotCleared count={notCleared} />}
      />
    )
  }
  if (rows === 0) return <EmptyState icon={CheckCircle2} title={none} description={noneText} />
  if (notCleared === 0) return null
  return (
    <p className="text-sm text-muted-foreground">
      <NotCleared count={notCleared} />
    </p>
  )
}

function CorrectDialog({
  difference,
  onClose,
}: {
  difference: RecordDifference | undefined
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [skipped, setSkipped] = useState<{ owner?: boolean; title?: boolean }>({})
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)

  const canOwner = difference?.versionOwner !== undefined
  const canTitle = difference?.versionTitle !== undefined
  const owner = canOwner && !skipped.owner
  const title = canTitle && !skipped.title

  const mutation = useMutation({
    mutationFn: (d: RecordDifference) => correctRecord(client, d, { owner, title }, reason),
    onSuccess: async () => {
      toast(t('retention.records.correct.done'))
      await invalidateAfterCorrection(queryClient)
      close()
    },
    // The difference went away or something entered the workflow: the row is out of date.
    onError: async (error) => {
      if (error instanceof ApiRequestError && error.status === 409) {
        await queryClient.invalidateQueries({ queryKey: ['records'] })
      }
    },
  })

  function close() {
    setSkipped({})
    setReason('')
    setAttempted(false)
    mutation.reset()
    onClose()
  }

  const noReason = reason.trim() === ''
  const noValue = !owner && !title
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (noReason || noValue) return
    mutation.mutate(difference)
  }

  return (
    <Dialog
      open={difference !== undefined}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('retention.records.correct.title')}</DialogTitle>
          <DialogDescription>
            {t('retention.records.correct.description', {
              version: difference?.versionLabel ?? '',
            })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <fieldset className="grid gap-2">
            <legend className="text-sm font-medium">{t('retention.records.correct.values')}</legend>
            {canOwner && difference.versionOwner && (
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-0.5 size-4"
                  checked={owner}
                  onChange={(e) => {
                    setSkipped((s) => ({ ...s, owner: !e.target.checked }))
                  }}
                />
                <span>
                  {t('retention.records.correct.owner')}: <UserName id={difference.versionOwner} />
                </span>
              </label>
            )}
            {canTitle && (
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  className="mt-0.5 size-4"
                  checked={title}
                  onChange={(e) => {
                    setSkipped((s) => ({ ...s, title: !e.target.checked }))
                  }}
                />
                <span>
                  {t('retention.records.correct.title_')}: {difference.versionTitle}
                </span>
              </label>
            )}
            {attempted && noValue && (
              <p role="alert" className="text-sm text-destructive">
                {t('retention.records.correct.nothing')}
              </p>
            )}
            {owner && (
              <p className="text-sm text-muted-foreground">
                {t('retention.records.correct.ownerHint')}
              </p>
            )}
          </fieldset>
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && noReason}
          />
          {mutation.isError && <InlineError error={mutation.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t('common.loading') : t('retention.records.correct.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function DifferenceRow({
  difference: d,
  onCorrect,
}: {
  difference: RecordDifference
  onCorrect: () => void
}) {
  return (
    <tr className="border-b last:border-0">
      <td className={td}>
        <Link
          to="/documents/$documentId"
          params={{ documentId: d.documentId }}
          search={{ version: d.versionId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {d.documentTitle}
        </Link>
      </td>
      <td className={`${td} font-mono text-xs`}>{d.versionLabel}</td>
      <td className={td}>
        <ul className="space-y-1">
          {d.versionOwner && (
            <li>
              {t('retention.records.differences.ownerDiffers')}:{' '}
              {d.documentOwner && <UserName id={d.documentOwner} />} →{' '}
              <UserName id={d.versionOwner} /> ({t('retention.records.differences.versionSays')})
            </li>
          )}
          {d.versionTitle && (
            <li>
              {t('retention.records.differences.titleDiffers')}: {d.documentTitle} →{' '}
              {d.versionTitle} ({t('retention.records.differences.versionSays')})
            </li>
          )}
        </ul>
      </td>
      <td className={td}>
        {d.inWorkflow ? (
          <span className="text-muted-foreground">
            {t('retention.records.differences.waits', { version: d.inWorkflow })}
          </span>
        ) : (
          <Button
            size="sm"
            variant="outline"
            aria-label={t('retention.records.differences.correctLabel', {
              title: d.documentTitle,
            })}
            onClick={onCorrect}
          >
            {t('retention.records.differences.correct')}
          </Button>
        )}
      </td>
    </tr>
  )
}

function DifferencesCard() {
  const client = useApiClient()
  const report = useQuery(recordDifferencesQuery(client))
  const [chosen, setChosen] = useState<RecordDifference>()
  const rows = report.data?.differences ?? []
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('retention.records.differences.title')}</CardTitle>
        <CardDescription>{t('retention.records.differences.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {report.isPending ? (
          <LoadingState label={t('retention.records.differences.loading')} />
        ) : report.isError ? (
          <ErrorState error={report.error} onRetry={() => void report.refetch()} />
        ) : (
          <>
            {rows.length > 0 && (
              <div className="relative overflow-x-auto">
                <table className="w-full text-sm">
                  <caption className="sr-only">
                    {t('retention.records.differences.caption')}
                  </caption>
                  <thead className="text-left text-xs text-muted-foreground">
                    <tr className="border-b">
                      <th scope="col" className={th}>
                        {t('retention.records.differences.colDocument')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.differences.colVersion')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.differences.colDiffers')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.differences.colAction')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((d) => (
                      <DifferenceRow
                        key={d.documentId}
                        difference={d}
                        onCorrect={() => {
                          setChosen(d)
                        }}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <ListEnd
              rows={rows.length}
              notCleared={report.data.notCleared}
              none={t('retention.records.differences.none')}
              noneText={t('retention.records.differences.noneText')}
            />
          </>
        )}
        <CorrectDialog
          difference={chosen}
          onClose={() => {
            setChosen(undefined)
          }}
        />
      </CardContent>
    </Card>
  )
}

function DraftRow({ draft: d }: { draft: DraftBelowBasis }) {
  return (
    <tr className="border-b last:border-0">
      <td className={td}>
        <Link
          to="/documents/$documentId"
          params={{ documentId: d.documentId }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {d.title}
        </Link>
      </td>
      <td className={td}>
        <span className="font-mono text-xs">{d.versionLabel}</span>
        <span className="block text-xs text-muted-foreground">{statusLabel(d.versionStatus)}</span>
        {d.versionStatus !== 'Draft' && (
          <span className="block text-xs text-destructive">
            {t('retention.records.drafts.frozen')}
          </span>
        )}
      </td>
      <td className={td}>{level(d.level)}</td>
      <td className={td}>
        {level(d.basisLevel)} <span className="font-mono text-xs">({d.basisLabel})</span>
      </td>
      <td className={td}>
        <UserName id={d.author} />
        <span className="block">
          {d.owner ? (
            <UserName id={d.owner} />
          ) : (
            <span className="text-muted-foreground">{t('retention.records.drafts.noOwner')}</span>
          )}
        </span>
      </td>
      <td className={td}>{formatDate(d.changedAt)}</td>
    </tr>
  )
}

function DraftsCard() {
  const client = useApiClient()
  const report = useQuery(draftsBelowBasisQuery(client))
  const rows = report.data?.drafts ?? []
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('retention.records.drafts.title')}</CardTitle>
        <CardDescription>{t('retention.records.drafts.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {report.isPending ? (
          <LoadingState label={t('retention.records.drafts.loading')} />
        ) : report.isError ? (
          <ErrorState error={report.error} onRetry={() => void report.refetch()} />
        ) : (
          <>
            {rows.length > 0 && (
              <div className="relative overflow-x-auto">
                <table className="w-full text-sm">
                  <caption className="sr-only">{t('retention.records.drafts.caption')}</caption>
                  <thead className="text-left text-xs text-muted-foreground">
                    <tr className="border-b">
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colDocument')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colVersion')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colLevel')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colBasis')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colPeople')}
                      </th>
                      <th scope="col" className={th}>
                        {t('retention.records.drafts.colChanged')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((d) => (
                      <DraftRow key={d.versionId} draft={d} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <ListEnd
              rows={rows.length}
              notCleared={report.data.notCleared}
              none={t('retention.records.drafts.none')}
              noneText={t('retention.records.drafts.noneText')}
            />
          </>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * The reports for records management (DMS-162, ADR 0084). They name people and reading them is
 * audited, so they load only when this tab is opened.
 */
export function RecordsSection() {
  return (
    <div className="space-y-6">
      <DifferencesCard />
      <DraftsCard />
    </div>
  )
}
