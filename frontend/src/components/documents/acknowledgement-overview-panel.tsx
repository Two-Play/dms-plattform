import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, Download } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import {
  acknowledgementOverviewQuery,
  downloadText,
  fetchRecipients,
  isTooSoon,
  recipientsCsv,
  sendReminder,
  type RecipientList,
  type RecipientStatus,
  type Recipients,
  type VersionAcknowledgementOverview,
} from '@/api/acknowledgement-overview'
import type { AcknowledgementPurpose } from '@/api/compliance'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { roleLabel } from '@/components/shared/role-label'
import { EmptyState, LoadingState } from '@/components/shared/states'
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
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { formatDateTime, formatPercent } from '@/i18n/format'
import { toast } from '@/lib/toast'

const MAX_REASON = 500

/** The recipients of a version as one line, e.g. "Units: HR · Groups: board". */
function recipientsText(r: Recipients): string {
  if (r.everyReader) return t('ackOverview.everyReader')
  return [
    (r.roles ?? []).length > 0 &&
      t('ackOverview.rolesLabel', { list: (r.roles ?? []).map(roleLabel).join(', ') }),
    (r.groups ?? []).length > 0 &&
      t('ackOverview.groupsLabel', { list: (r.groups ?? []).join(', ') }),
    (r.units ?? []).length > 0 && t('ackOverview.unitsLabel', { list: (r.units ?? []).join(', ') }),
  ]
    .filter(Boolean)
    .join(' · ')
}

function sourceText(source: string): string {
  switch (source) {
    case 'class':
      return t('ackOverview.sourceClass')
    case 'tenant':
      return t('ackOverview.sourceTenant')
    default:
      return t('ackOverview.sourceDefault')
  }
}

function statusText(status: string): string {
  return status === 'done' ? t('ackOverview.statusDone') : t('ackOverview.statusOpen')
}

/** Asks for purpose and reason, then lists the recipients with a filter and CSV export. */
function RecipientsDialog({
  documentId,
  title,
  version,
  onClose,
}: {
  documentId: string
  title: string
  version: VersionAcknowledgementOverview | undefined
  onClose: () => void
}) {
  const client = useApiClient()
  const id = useId()
  const [purpose, setPurpose] = useState<AcknowledgementPurpose>('reminder')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [filter, setFilter] = useState<RecipientStatus | 'all'>('open')
  const [result, setResult] = useState<RecipientList | undefined>(undefined)
  const reasonMissing = reason.trim() === ''

  const load = useMutation({
    mutationFn: (v: VersionAcknowledgementOverview) =>
      fetchRecipients(client, documentId, v.versionId, purpose, reason.trim()),
    onSuccess: setResult,
  })

  function close() {
    setReason('')
    setAttempted(false)
    setResult(undefined)
    setFilter('open')
    load.reset()
    onClose()
  }

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing || !version) return
    load.mutate(version)
  }

  const shown = (result?.recipients ?? []).filter((r) => filter === 'all' || r.status === filter)
  const reasonError = attempted && reasonMissing

  function exportCsv() {
    if (!result) return
    const csv = recipientsCsv(
      { ...result, recipients: shown },
      {
        name: t('ackOverview.person'),
        userId: t('ackOverview.userId'),
        status: t('ackOverview.status'),
        acknowledgedAt: t('ackOverview.confirmedAt'),
      },
      statusText,
    )
    const name = title.replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '')
    downloadText(csv, `read-confirmations-${name}-${result.versionLabel}-${filter}.csv`)
  }

  return (
    <Dialog
      open={version !== undefined}
      onOpenChange={(open) => {
        if (!open) close()
      }}
    >
      {version && (
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {t('ackOverview.dialogTitle', { version: version.versionLabel })}
            </DialogTitle>
            <DialogDescription>{t('ackOverview.dialogDescription')}</DialogDescription>
          </DialogHeader>
          {result ? (
            <div className="grid gap-3">
              <fieldset className="flex flex-wrap items-center gap-3 text-sm">
                <legend className="sr-only">{t('ackOverview.filter')}</legend>
                <span aria-hidden className="font-medium">
                  {t('ackOverview.filter')}:
                </span>
                {(
                  [
                    ['open', t('ackOverview.filterOpen'), result.open],
                    ['done', t('ackOverview.filterDone'), result.acknowledged],
                    ['all', t('ackOverview.filterAll'), result.audience],
                  ] as const
                ).map(([value, label, count]) => (
                  <label key={value} className="flex items-center gap-1.5">
                    <input
                      type="radio"
                      name={`${id}-filter`}
                      value={value}
                      checked={filter === value}
                      onChange={() => {
                        setFilter(value)
                      }}
                    />
                    {label} ({count})
                  </label>
                ))}
              </fieldset>
              {shown.length === 0 ? (
                <EmptyState
                  icon={CheckCircle2}
                  title={
                    filter === 'open'
                      ? t('ackOverview.emptyOpen')
                      : filter === 'done'
                        ? t('ackOverview.emptyDone')
                        : t('ackOverview.emptyAll')
                  }
                />
              ) : (
                <div className="max-h-80 overflow-auto">
                  <table className="w-full text-sm">
                    <caption className="sr-only">
                      {t('ackOverview.peopleCaption', { version: result.versionLabel })}
                    </caption>
                    <thead>
                      <tr className="border-b text-left text-muted-foreground">
                        <th scope="col" className="py-2 pr-3 font-medium">
                          {t('ackOverview.person')}
                        </th>
                        <th scope="col" className="py-2 pr-3 font-medium">
                          {t('ackOverview.status')}
                        </th>
                        <th scope="col" className="py-2 font-medium">
                          {t('ackOverview.confirmedAt')}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map((r) => (
                        <tr key={r.userId} className="border-b last:border-0">
                          <td className="py-2 pr-3" title={r.userId}>
                            {r.displayName ?? r.userId}
                          </td>
                          <td className="py-2 pr-3">{statusText(r.status)}</td>
                          <td className="py-2">
                            {r.acknowledgedAt ? formatDateTime(r.acknowledgedAt) : '–'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={exportCsv}
                  disabled={shown.length === 0}
                  aria-label={t('ackOverview.exportLabel')}
                >
                  <Download aria-hidden="true" />
                  {t('ackOverview.export')}
                </Button>
                <Button type="button" onClick={close}>
                  {t('common.close')}
                </Button>
              </DialogFooter>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4" noValidate>
              <fieldset className="grid gap-2">
                <legend className="mb-1 text-sm font-medium">{t('ackOverview.purpose')}</legend>
                {(
                  [
                    ['reminder', t('ackOverview.reminder')],
                    ['escalation', t('ackOverview.escalation')],
                  ] as const
                ).map(([value, label]) => (
                  <label key={value} className="flex items-center gap-2 text-sm">
                    <input
                      type="radio"
                      name={`${id}-purpose`}
                      value={value}
                      checked={purpose === value}
                      onChange={() => {
                        setPurpose(value)
                      }}
                    />
                    {label}
                  </label>
                ))}
              </fieldset>
              <div className="grid gap-1.5">
                <Label htmlFor={`${id}-reason`}>{t('ackOverview.reason')}</Label>
                <Textarea
                  id={`${id}-reason`}
                  value={reason}
                  maxLength={MAX_REASON}
                  onChange={(e) => {
                    setReason(e.target.value)
                  }}
                  aria-invalid={reasonError || undefined}
                  aria-required
                  aria-describedby={`${id}-reason-hint`}
                />
                <p
                  id={`${id}-reason-hint`}
                  role={reasonError ? 'alert' : undefined}
                  className={
                    reasonError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'
                  }
                >
                  {reasonError
                    ? t('ackOverview.reasonMissing')
                    : t('ackOverview.reasonHint', { max: MAX_REASON })}
                </p>
              </div>
              {load.isError && <InlineError error={load.error} />}
              <DialogFooter>
                <Button type="button" variant="outline" onClick={close}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={load.isPending}>
                  {load.isPending ? t('common.loading') : t('ackOverview.show')}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      )}
    </Dialog>
  )
}

function VersionFigures({
  documentId,
  version,
  onShowNames,
}: {
  documentId: string
  version: VersionAcknowledgementOverview
  onShowNames: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const remind = useMutation({
    mutationFn: () => sendReminder(client, documentId, version.versionId),
    onSuccess: async (sent) => {
      toast(t('ackOverview.reminded', { count: sent.reminded }))
      await queryClient.invalidateQueries({
        queryKey: ['acknowledgements', 'overview', documentId],
      })
    },
    onError: (e) => {
      if (isTooSoon(e)) toast(t('ackOverview.tooSoon'), 'error')
    },
  })
  const nothingOpen =
    remind.error instanceof ApiRequestError && remind.error.code === 'nothing_open'
  const blocked = version.nextReminderAt !== undefined || version.open === 0

  return (
    <section
      aria-label={t('ackOverview.versionCaption', { version: version.versionLabel })}
      className="space-y-3"
    >
      <dl className="grid grid-cols-3 gap-3 text-center">
        <div className="rounded-md border p-2">
          <dt className="text-xs text-muted-foreground">{t('ackOverview.audience')}</dt>
          <dd className="text-lg font-semibold">{version.audience}</dd>
        </div>
        <div className="rounded-md border p-2">
          <dt className="text-xs text-muted-foreground">{t('ackOverview.acknowledged')}</dt>
          <dd className="text-lg font-semibold">
            {version.acknowledged}
            {version.rate !== undefined && (
              <span className="ml-1 text-xs font-normal text-muted-foreground">
                ({formatPercent(version.rate)})
              </span>
            )}
          </dd>
        </div>
        <div className="rounded-md border p-2">
          <dt className="text-xs text-muted-foreground">{t('ackOverview.open')}</dt>
          <dd className="text-lg font-semibold">{version.open}</dd>
        </div>
      </dl>
      <dl className="space-y-1 text-sm">
        <div>
          <dt className="inline font-medium">{t('ackOverview.recipients')}: </dt>
          <dd className="inline">
            {recipientsText(version.recipients)}{' '}
            <span className="text-muted-foreground">({sourceText(version.recipients.source)})</span>
          </dd>
        </div>
        <div>
          <dt className="inline font-medium">{t('ackOverview.reminders')}: </dt>
          <dd className="inline">
            {version.reminderDays > 0
              ? t('ackOverview.reminderEvery', { count: version.reminderDays })
              : t('ackOverview.reminderOff')}
          </dd>
        </div>
      </dl>
      {version.lastReminderAt && (
        <p className="text-xs text-muted-foreground">
          {t('ackOverview.lastReminder', { date: formatDateTime(version.lastReminderAt) })}{' '}
          {version.nextReminderAt &&
            t('ackOverview.nextReminder', { date: formatDateTime(version.nextReminderAt) })}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={onShowNames}
          disabled={version.audience === 0}
          aria-label={t('ackOverview.showNamesLabel', { version: version.versionLabel })}
        >
          {t('ackOverview.showNames')}
        </Button>
        <Button
          size="sm"
          onClick={() => {
            remind.mutate()
          }}
          disabled={blocked || remind.isPending}
          aria-label={t('ackOverview.remindLabel', { version: version.versionLabel })}
        >
          {remind.isPending ? t('ackOverview.reminding') : t('ackOverview.remind')}
        </Button>
      </div>
      {nothingOpen && (
        <p role="status" className="text-sm text-muted-foreground">
          {t('ackOverview.nothingOpen')}
        </p>
      )}
      {remind.isError && !nothingOpen && !isTooSoon(remind.error) && (
        <InlineError error={remind.error} />
      )}
    </section>
  )
}

/**
 * Read confirmations of a document for its owner and compliance officers (DMS-100): recipients,
 * confirmed and open per valid version, manual reminders, and the people behind the figures after
 * stating a purpose. Callers who may not see it get nothing rendered (the backend answers 403).
 */
export function AcknowledgementOverviewPanel({ documentId }: { documentId: string }) {
  const client = useApiClient()
  const overview = useQuery(acknowledgementOverviewQuery(client, documentId))
  const [names, setNames] = useState<VersionAcknowledgementOverview | undefined>(undefined)

  if (overview.data === null) return null
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('ackOverview.title')}</CardTitle>
        <CardDescription>{t('ackOverview.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {overview.isPending && <LoadingState label={t('ackOverview.loading')} rows={2} />}
        {overview.isError && (
          <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
        )}
        {overview.data && (overview.data.versions ?? []).length === 0 && (
          <EmptyState icon={CheckCircle2} title={t('ackOverview.none')} />
        )}
        {(overview.data?.versions ?? []).map((v) => (
          <VersionFigures
            key={v.versionId}
            documentId={documentId}
            version={v}
            onShowNames={() => {
              setNames(v)
            }}
          />
        ))}
      </CardContent>
      <RecipientsDialog
        documentId={documentId}
        title={overview.data?.title ?? documentId}
        version={names}
        onClose={() => {
          setNames(undefined)
        }}
      />
    </Card>
  )
}
