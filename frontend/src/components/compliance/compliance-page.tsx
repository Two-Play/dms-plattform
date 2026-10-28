import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useSearch } from '@tanstack/react-router'
import { X } from 'lucide-react'
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
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { groupLabel, type CompliancePageSearch } from './compliance-params'

const NO_UNIT = 'No unit'
const NO_OWNER = 'No owner'
const MAX_REASON = 500

const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })

/** A YYYY-MM-DD date without shifting it into the browser's time zone. */
function formatDate(date: string): string {
  return dateOnly.format(new Date(`${date}T00:00:00Z`))
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
      aria-label={`${label}: ${value}. Show the list`}
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

  if (overview.isPending) return <Skeleton className="h-32 w-full" />
  if (overview.isError) {
    return (
      <p role="alert" className="text-sm text-destructive">
        {overview.error.message}
      </p>
    )
  }
  const o = overview.data
  const acks = o.acknowledgements
  const byUnit = o.overdueByUnit ?? []
  const byOwner = o.overdueByOwner ?? []
  const ratesByUnit = o.acknowledgementsByUnit ?? []
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">As of {formatDate(o.today)}</p>
      <div className="grid gap-4 sm:grid-cols-3">
        <KeyFigure
          label="Overdue reviews"
          value={String(o.overdueReviews)}
          detail="Valid versions whose review date has passed without recertification"
          search={{ view: 'reviews' }}
        />
        <KeyFigure
          label="Acknowledgement rate"
          value={formatRate(acks.rate)}
          detail={`${String(acks.acknowledged)} of ${String(acks.audience)} readers across ${String(acks.versions)} versions`}
          search={{ view: 'acknowledgements' }}
        />
        <KeyFigure
          label="Open acknowledgements"
          value={String(acks.open)}
          detail="Readers who have not acknowledged a valid version yet"
          search={{ view: 'acknowledgements' }}
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle>Overdue reviews by unit</CardTitle>
          </CardHeader>
          <CardContent>
            {byUnit.length === 0 ? (
              <p className="text-sm text-muted-foreground">No overdue reviews.</p>
            ) : (
              <Table caption="Overdue reviews by organizational unit" head={['Unit', 'Overdue']}>
                {byUnit.map((u) => (
                  <tr key={u.unit ?? ''} className="border-b last:border-0">
                    <td className={td}>{groupLabel(u.unit, NO_UNIT)}</td>
                    <td className={td}>
                      <Link
                        to="/compliance"
                        search={{ view: 'reviews', unit: u.unit ?? '' }}
                        className={linkClass}
                        aria-label={`${String(u.count)} overdue in ${groupLabel(u.unit, NO_UNIT)}`}
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
            <CardTitle>Overdue reviews by owner</CardTitle>
          </CardHeader>
          <CardContent>
            {byOwner.length === 0 ? (
              <p className="text-sm text-muted-foreground">No overdue reviews.</p>
            ) : (
              <Table caption="Overdue reviews by owner" head={['Owner', 'Overdue']}>
                {byOwner.map((u) => (
                  <tr key={u.owner ?? ''} className="border-b last:border-0">
                    <td className={td}>{groupLabel(u.owner, NO_OWNER)}</td>
                    <td className={td}>
                      <Link
                        to="/compliance"
                        search={{ view: 'reviews', owner: u.owner ?? '' }}
                        className={linkClass}
                        aria-label={`${String(u.count)} overdue of ${groupLabel(u.owner, NO_OWNER)}`}
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
            <CardTitle>Acknowledgements by unit</CardTitle>
          </CardHeader>
          <CardContent>
            {ratesByUnit.length === 0 ? (
              <p className="text-sm text-muted-foreground">No versions require acknowledgement.</p>
            ) : (
              <Table
                caption="Acknowledgement rate by organizational unit"
                head={['Unit', 'Rate', 'Open']}
              >
                {ratesByUnit.map((u) => (
                  <tr key={u.unit ?? ''} className="border-b last:border-0">
                    <td className={td}>{groupLabel(u.unit, NO_UNIT)}</td>
                    <td className={td}>
                      <Link
                        to="/compliance"
                        search={{ view: 'acknowledgements', unit: u.unit ?? '' }}
                        className={linkClass}
                        aria-label={`Acknowledgement rate ${formatRate(u.rate.rate)} in ${groupLabel(u.unit, NO_UNIT)}`}
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
    search.unit !== undefined ? `unit ${groupLabel(search.unit, NO_UNIT)}` : undefined,
    search.owner !== undefined ? `owner ${groupLabel(search.owner, NO_OWNER)}` : undefined,
    search.document !== undefined ? 'one document' : undefined,
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
          <span>Filtered by {filter}</span>
          <Link
            to="/compliance"
            search={{ view: search.view }}
            className="inline-flex items-center gap-1 underline-offset-4 hover:underline"
          >
            <X className="size-3" aria-hidden />
            Clear filter
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
      <ListHeader title="Overdue reviews" search={search} />
      <CardContent>
        {list.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : list.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {list.error.message}
          </p>
        ) : list.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No overdue reviews.</p>
        ) : (
          <Table
            caption="Overdue reviews"
            head={['Document', 'Version', 'Unit', 'Owner', 'Review date', 'Overdue']}
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
                <td className={td}>{groupLabel(r.unit, NO_UNIT)}</td>
                <td className={td}>{groupLabel(r.owner, NO_OWNER)}</td>
                <td className={td}>{formatDate(r.dueOn)}</td>
                <td className={td}>
                  {r.daysOverdue === 0 ? 'Due today' : `${String(r.daysOverdue)} days`}
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
              Who has not acknowledged: {version.title} {version.versionLabel}
            </DialogTitle>
            <DialogDescription>
              Names are shown only for a stated purpose. Your purpose and reason are recorded in the
              audit trail; do not use the list to monitor performance or behaviour.
            </DialogDescription>
          </DialogHeader>
          {result ? (
            <div className="grid gap-3">
              <p className="text-sm">
                {(result.open ?? []).length} of {result.audience} readers have not acknowledged yet.
              </p>
              {(result.open ?? []).length > 0 && (
                <ul aria-label="Readers without acknowledgement" className="grid gap-1 text-sm">
                  {(result.open ?? []).map((user) => (
                    <li key={user} className="font-mono text-xs">
                      {user}
                    </li>
                  ))}
                </ul>
              )}
              <DialogFooter>
                <Button type="button" onClick={close}>
                  Close
                </Button>
              </DialogFooter>
            </div>
          ) : (
            <form onSubmit={submit} className="grid gap-4" noValidate>
              <fieldset className="grid gap-2">
                <legend className="mb-1 text-sm font-medium">Purpose *</legend>
                {(
                  [
                    ['escalation', 'Escalation to the responsible management'],
                    ['reminder', 'Reminder to the readers'],
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
                <Label htmlFor={`${id}-reason`}>Reason *</Label>
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
                    ? 'State why you need the names.'
                    : `Up to ${String(MAX_REASON)} characters, without personal data.`}
                </p>
              </div>
              {mutation.isError && (
                <p role="alert" className="text-sm text-destructive">
                  {mutation.error.message}
                </p>
              )}
              <DialogFooter>
                <Button type="button" variant="outline" onClick={close}>
                  Cancel
                </Button>
                <Button type="submit" disabled={mutation.isPending}>
                  {mutation.isPending ? 'Loading…' : 'Show names'}
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
      <ListHeader title="Acknowledgements" search={search} />
      <CardContent>
        {list.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : list.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {list.error.message}
          </p>
        ) : list.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No versions require acknowledgement.</p>
        ) : (
          <Table
            caption="Acknowledgement rate per version"
            head={['Document', 'Version', 'Unit', 'Acknowledged', 'Rate', 'Open', '']}
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
                <td className={td}>{groupLabel(r.unit, NO_UNIT)}</td>
                <td className={td}>
                  {r.acknowledged} of {r.audience}
                </td>
                <td className={td}>{formatRate(r.rate)}</td>
                <td className={td}>{r.open}</td>
                <td className="py-2">
                  {r.open > 0 && (
                    <Button
                      variant="outline"
                      size="sm"
                      aria-label={`Who has not acknowledged ${r.title} ${r.versionLabel}`}
                      onClick={() => {
                        setNames(r)
                      }}
                    >
                      Who has not acknowledged
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
      <h1 className="font-heading text-2xl font-semibold">Compliance cockpit</h1>
      <Overview />
      {search.view === 'reviews' && <OverdueReviewList search={search} />}
      {search.view === 'acknowledgements' && <AcknowledgementList search={search} />}
    </div>
  )
}
