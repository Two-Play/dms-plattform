import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useSearch } from '@tanstack/react-router'
import { CheckCircle2, X } from 'lucide-react'
import { useId, useState, type ReactNode, type SyntheticEvent } from 'react'
import {
  acknowledgementRatesQuery,
  complianceOverviewQuery,
  fetchOpenAcknowledgements,
  formatRate,
  overdueReviewsQuery,
  type AcknowledgementPurpose,
  type OpenAcknowledgements,
  type VersionRate,
} from '@/api/compliance'
import { useApiClient } from '@/api/context'
import { statusLabel } from '@/components/documents/status-label'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
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
import { useUserLabel } from '@/api/users'
import { UserName } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatPlainDate as formatDate } from '@/i18n/format'
import { groupLabel, type CompliancePageSearch } from './compliance-params'

const MAX_REASON = 500

const noUnit = () => t('compliance.noUnit')

/** An owner (user id) by display name; "No owner" for versions without one. */
function OwnerName({ owner }: { owner: string | undefined }) {
  return owner ? <UserName id={owner} /> : <>{t('compliance.noOwner')}</>
}

/** The link to the overdue reviews of an owner, labelled with the owner's name. */
function OwnerCountLink({ owner, count }: { owner: string | undefined; count: number }) {
  const name = useUserLabel(owner) ?? t('compliance.noOwner')
  return (
    <Link
      to="/compliance"
      search={{ view: 'reviews', owner: owner ?? '' }}
      className={linkClass}
      aria-label={t('compliance.overdueOf', { count, owner: name })}
    >
      {count}
    </Link>
  )
}

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4'
const linkClass = 'font-medium underline-offset-4 hover:underline'

function Table({
  caption,
  head,
  children,
}: {
  caption: string
  head: string[]
  children: ReactNode
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            {head.map((h) => (
              <th key={h} scope="col" className={th}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  )
}

/** A key figure that leads to its list. */
function KeyFigure({
  label,
  value,
  detail,
  search,
}: {
  label: string
  value: string
  detail: string
  search: CompliancePageSearch
}) {
  return (
    <Link
      to="/compliance"
      search={search}
      aria-label={t('compliance.showList', { label, value })}
      className="block rounded-xl focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <Card className="h-full transition-colors hover:bg-accent/50">
        <CardHeader>
          <CardDescription>{label}</CardDescription>
          <CardTitle className="font-heading text-3xl">{value}</CardTitle>
        </CardHeader>
        <CardContent className="text-xs text-muted-foreground">{detail}</CardContent>
      </Card>
    </Link>
  )
}

function Overview() {
  const client = useApiClient()
  const overview = useQuery(complianceOverviewQuery(client))

  if (overview.isPending) return <LoadingState label={t('compliance.loadingOverview')} />
  if (overview.isError) {
    return <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
  }
  const o = overview.data
  const acks = o.acknowledgements
  const byUnit = o.overdueByUnit ?? []
  const byOwner = o.overdueByOwner ?? []
  const ratesByUnit = o.acknowledgementsByUnit ?? []
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">
        {t('compliance.asOf', { date: formatDate(o.today) })}
      </p>
      <div className="grid gap-4 sm:grid-cols-3">
        <KeyFigure
          label={t('compliance.overdueReviews')}
          value={String(o.overdueReviews)}
          detail={t('compliance.overdueDetail')}
          search={{ view: 'reviews' }}
        />
        <KeyFigure
          label={t('compliance.ackRate')}
          value={formatRate(acks.rate)}
          detail={t('compliance.ackRateDetail', {
            acknowledged: acks.acknowledged,
            audience: acks.audience,
            versions: acks.versions,
          })}
          search={{ view: 'acknowledgements' }}
        />
        <KeyFigure
          label={t('compliance.openAcks')}
          value={String(acks.open)}
          detail={t('compliance.openAcksDetail')}
          search={{ view: 'acknowledgements' }}
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle>{t('compliance.byUnit')}</CardTitle>
          </CardHeader>
          <CardContent>
            {byUnit.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('compliance.noOverdue')}</p>
            ) : (
              <Table
                caption={t('compliance.byUnitCaption')}
                head={[t('compliance.unit'), t('compliance.overdue')]}
              >
                {byUnit.map((u) => (
                  <tr key={u.unit ?? ''} className="border-b last:border-0">
                    <td className={td}>{groupLabel(u.unit, noUnit())}</td>
                    <td className={td}>
                      <Link
                        to="/compliance"
                        search={{ view: 'reviews', unit: u.unit ?? '' }}
                        className={linkClass}
                        aria-label={t('compliance.overdueIn', {
                          count: u.count,
                          unit: groupLabel(u.unit, noUnit()),
                        })}
                      >
                        {u.count}
                      </Link>
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t('compliance.byOwner')}</CardTitle>
          </CardHeader>
          <CardContent>
            {byOwner.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('compliance.noOverdue')}</p>
            ) : (
              <Table
                caption={t('compliance.byOwner')}
                head={[t('compliance.owner'), t('compliance.overdue')]}
              >
                {byOwner.map((u) => (
                  <tr key={u.owner ?? ''} className="border-b last:border-0">
                    <td className={td}>
                      <OwnerName owner={u.owner || undefined} />
                    </td>
                    <td className={td}>
                      <OwnerCountLink owner={u.owner || undefined} count={u.count} />
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t('compliance.acksByUnit')}</CardTitle>
          </CardHeader>
          <CardContent>
            {ratesByUnit.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('compliance.noAckVersions')}</p>
            ) : (
              <Table
                caption={t('compliance.ratesCaption')}
                head={[t('compliance.unit'), t('compliance.rate'), t('compliance.open')]}
              >
                {ratesByUnit.map((u) => (
                  <tr key={u.unit ?? ''} className="border-b last:border-0">
                    <td className={td}>{groupLabel(u.unit, noUnit())}</td>
                    <td className={td}>
                      <Link
                        to="/compliance"
                        search={{ view: 'acknowledgements', unit: u.unit ?? '' }}
                        className={linkClass}
                        aria-label={t('compliance.rateIn', {
                          rate: formatRate(u.rate.rate),
                          unit: groupLabel(u.unit, noUnit()),
                        })}
                      >
                        {formatRate(u.rate.rate)}
                      </Link>
                    </td>
                    <td className={td}>{u.rate.open}</td>
                  </tr>
                ))}
              </Table>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

function filterText(search: CompliancePageSearch): string | undefined {
  const parts = [
    search.unit !== undefined
      ? t('compliance.filterUnit', { unit: groupLabel(search.unit, noUnit()) })
      : undefined,
    search.owner !== undefined
      ? t('compliance.filterOwner', { owner: groupLabel(search.owner, t('compliance.noOwner')) })
      : undefined,
    search.document !== undefined ? t('compliance.filterDocument') : undefined,
  ].filter((p) => p !== undefined)
  return parts.length === 0 ? undefined : parts.join(', ')
}

function ListHeader({ title, search }: { title: string; search: CompliancePageSearch }) {
  const filter = filterText(search)
  return (
    <CardHeader>
      <CardTitle>
        <h2>{title}</h2>
      </CardTitle>
      {filter && (
        <CardDescription className="flex flex-wrap items-center gap-2">
          <span>
            {t('compliance.filteredBy')} {filter}
          </span>
          <Link
            to="/compliance"
            search={{ view: search.view }}
            className="inline-flex items-center gap-1 underline-offset-4 hover:underline"
          >
            <X className="size-3" aria-hidden />
            {t('compliance.clearFilter')}
          </Link>
        </CardDescription>
      )}
    </CardHeader>
  )
}

function OverdueReviewList({ search }: { search: CompliancePageSearch }) {
  const client = useApiClient()
  const list = useQuery(overdueReviewsQuery(client, search.unit, search.owner))
  return (
    <Card>
      <ListHeader title={t('compliance.overdueReviews')} search={search} />
      <CardContent>
        {list.isPending ? (
          <LoadingState label={t('compliance.loadingList')} />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.length === 0 ? (
          <EmptyState
            icon={CheckCircle2}
            title={t('compliance.noOverdue')}
            description={t('compliance.noOverdueText')}
          />
        ) : (
          <Table
            caption={t('compliance.overdueReviews')}
            head={[
              t('compliance.document'),
              t('compliance.version'),
              t('compliance.unit'),
              t('compliance.owner'),
              t('compliance.reviewDate'),
              t('compliance.overdue'),
            ]}
          >
            {list.data.map((r) => (
              <tr key={r.versionId} className="border-b last:border-0">
                <td className={td}>
                  <Link
                    to="/documents/$documentId"
                    params={{ documentId: r.documentId }}
                    className={linkClass}
                  >
                    {r.title}
                  </Link>
                </td>
                <td className={`${td} font-mono text-xs`}>
                  {r.versionLabel}
                  <span className="sr-only">, {statusLabel(r.status)}</span>
                </td>
                <td className={td}>{groupLabel(r.unit, noUnit())}</td>
                <td className={td}>
                  <OwnerName owner={r.owner || undefined} />
                </td>
                <td className={td}>{formatDate(r.dueOn)}</td>
                <td className={td}>
                  {r.daysOverdue === 0
                    ? t('compliance.dueToday')
                    : t('compliance.days', { count: r.daysOverdue })}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </CardContent>
    </Card>
  )
}

/** Asks for purpose and reason, then shows who has not acknowledged the version. */
function OpenAcknowledgementsDialog({
  version,
  onClose,
}: {
  version: VersionRate | undefined
  onClose: () => void
}) {
  const client = useApiClient()
  const id = useId()
  const [purpose, setPurpose] = useState<AcknowledgementPurpose>('escalation')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const [result, setResult] = useState<OpenAcknowledgements | undefined>(undefined)
  const reasonMissing = reason.trim() === ''

  const mutation = useMutation({
    mutationFn: (v: VersionRate) =>
      fetchOpenAcknowledgements(client, v.documentId, v.versionId, purpose, reason.trim()),
    onSuccess: setResult,
  })

  function close() {
    setReason('')
    setAttempted(false)
    setResult(undefined)
    mutation.reset()
    onClose()
  }

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing || !version) return
    mutation.mutate(version)
  }

  const reasonError = attempted && reasonMissing
  return (
    <Dialog
      open={version !== undefined}
      onOpenChange={(open) => {
        if (!open) close()
      }}
    >
      {version && (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t('compliance.dialog.title', {
                title: version.title,
                version: version.versionLabel,
              })}
            </DialogTitle>
            <DialogDescription>{t('compliance.dialog.description')}</DialogDescription>
          </DialogHeader>
          {result ? (
            <div className="grid gap-3">
              <p className="text-sm">
                {t('compliance.dialog.summary', {
                  open: (result.open ?? []).length,
                  audience: result.audience,
                })}
              </p>
              {(result.open ?? []).length > 0 && (
                <ul aria-label={t('compliance.dialog.readers')} className="grid gap-1 text-sm">
                  {(result.open ?? []).map((user) => (
                    <li key={user}>
                      <UserName id={user} />
                    </li>
                  ))}
                </ul>
              )}
              <DialogFooter>
                <Button type="button" onClick={close}>
                  {t('common.close')}
                </Button>
              </DialogFooter>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4" noValidate>
              <fieldset className="grid gap-2">
                <legend className="mb-1 text-sm font-medium">
                  {t('compliance.dialog.purpose')}
                </legend>
                {(
                  [
                    ['escalation', t('compliance.dialog.escalation')],
                    ['reminder', t('compliance.dialog.reminder')],
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
                <Label htmlFor={`${id}-reason`}>{t('compliance.dialog.reason')}</Label>
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
                    ? t('compliance.dialog.reasonMissing')
                    : t('compliance.dialog.reasonHint', { max: MAX_REASON })}
                </p>
              </div>
              {mutation.isError && <InlineError error={mutation.error} />}
              <DialogFooter>
                <Button type="button" variant="outline" onClick={close}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={mutation.isPending}>
                  {mutation.isPending ? t('common.loading') : t('compliance.dialog.show')}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      )}
    </Dialog>
  )
}

function AcknowledgementList({ search }: { search: CompliancePageSearch }) {
  const client = useApiClient()
  const list = useQuery(acknowledgementRatesQuery(client, search.unit, search.document))
  const [names, setNames] = useState<VersionRate | undefined>(undefined)
  return (
    <Card>
      <ListHeader title={t('compliance.acknowledgements')} search={search} />
      <CardContent>
        {list.isPending ? (
          <LoadingState label={t('compliance.loadingList')} />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.length === 0 ? (
          <EmptyState
            icon={CheckCircle2}
            title={t('compliance.noAckVersions')}
            description={t('compliance.noAckText')}
          />
        ) : (
          <Table
            caption={t('compliance.perVersion')}
            head={[
              t('compliance.document'),
              t('compliance.version'),
              t('compliance.unit'),
              t('compliance.acknowledged'),
              t('compliance.rate'),
              t('compliance.open'),
              '',
            ]}
          >
            {list.data.map((r) => (
              <tr key={r.versionId} className="border-b last:border-0">
                <td className={td}>
                  <Link
                    to="/documents/$documentId"
                    params={{ documentId: r.documentId }}
                    className={linkClass}
                  >
                    {r.title}
                  </Link>
                </td>
                <td className={`${td} font-mono text-xs`}>{r.versionLabel}</td>
                <td className={td}>{groupLabel(r.unit, noUnit())}</td>
                <td className={td}>
                  {t('compliance.ofAudience', {
                    acknowledged: r.acknowledged,
                    audience: r.audience,
                  })}
                </td>
                <td className={td}>{formatRate(r.rate)}</td>
                <td className={td}>{r.open}</td>
                <td className="py-2">
                  {r.open > 0 && (
                    <Button
                      variant="outline"
                      size="sm"
                      aria-label={t('compliance.whoLabel', {
                        title: r.title,
                        version: r.versionLabel,
                      })}
                      onClick={() => {
                        setNames(r)
                      }}
                    >
                      {t('compliance.who')}
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </CardContent>
      <OpenAcknowledgementsDialog
        version={names}
        onClose={() => {
          setNames(undefined)
        }}
      />
    </Card>
  )
}

/** Compliance cockpit (US-15.1): key figures, figures per unit and owner, and their lists. */
export function CompliancePage() {
  const search = useSearch({ from: '/_app/compliance' })
  return (
    <div className="space-y-6">
      <PageHeader title={t('compliance.title')} description={t('compliance.description')} />
      <Overview />
      {search.view === 'reviews' && <OverdueReviewList search={search} />}
      {search.view === 'acknowledgements' && <AcknowledgementList search={search} />}
    </div>
  )
}
