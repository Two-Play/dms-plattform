import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { Archive, Plus, Trash2 } from 'lucide-react'
import { useId, useMemo, useState, type ReactNode, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { useMe } from '@/api/me'
import {
  RETENTION_TRIGGERS,
  RULE_KEY,
  MAX_PERIOD_MONTHS,
  MAX_PROPOSAL_ITEMS,
  canManageRetention,
  cleanupRunsQuery,
  decideDestruction,
  destructionListQuery,
  distinct,
  filterCandidates,
  invalidateAfterDestruction,
  isFourEyes,
  proposalsQuery,
  proposeDestruction,
  retentionRulesQuery,
  runStorageCleanup,
  saveRetentionRule,
  type CleanupReport,
  type DestructionCandidate,
  type DestructionItem,
  type DestructionProposal,
  type ProposalStatus,
  type RetentionRule,
  type RetentionTrigger,
} from '@/api/retention'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { UserName } from '@/components/shared/user-name'
import { Badge } from '@/components/ui/badge'
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
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { formatDate, formatDateTime } from '@/i18n/format'
import { toast } from '@/lib/toast'
import type { RetentionPageSearch } from './retention-params'

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4 align-top'
const selectClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

/** Longest reason the backend accepts (`DestructionService.MaxReasonLength`). */
const MAX_REASON = 4000

type Tab = 'list' | 'proposals' | 'rules' | 'cleanup'
const TABS: readonly Tab[] = ['list', 'proposals', 'rules', 'cleanup']

function Table({
  caption,
  head,
  children,
}: {
  caption: string
  head: ReactNode[]
  children: ReactNode
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="text-left text-xs text-muted-foreground">
          <tr className="border-b">
            {head.map((h, i) => (
              <th key={i} scope="col" className={th}>
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

/** The required reason of every proposal and decision; recorded in the audit trail. */
function ReasonField({
  id,
  value,
  onChange,
  invalid,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  invalid: boolean
}) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id}>{t('retention.reasonField')}</Label>
      <Textarea
        id={id}
        value={value}
        maxLength={MAX_REASON}
        onChange={(e) => {
          onChange(e.target.value)
        }}
        aria-invalid={invalid || undefined}
        aria-required
        aria-describedby={`${id}-hint`}
      />
      <p
        id={`${id}-hint`}
        role={invalid ? 'alert' : undefined}
        className={invalid ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
      >
        {invalid ? t('retention.reasonMissing') : t('retention.reasonHint', { max: MAX_REASON })}
      </p>
    </div>
  )
}

function DocumentTitle({ title, number }: { title?: string; number?: string }) {
  return (
    <>
      {title ?? <span className="text-muted-foreground">{t('retention.hiddenTitle')}</span>}
      {number && <span className="block font-mono text-xs text-muted-foreground">{number}</span>}
    </>
  )
}

// Destruction list

function ProposeDialog({
  versionIds,
  onClose,
  onDone,
}: {
  versionIds: string[] | undefined
  onClose: () => void
  onDone: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const count = versionIds?.length ?? 0

  const mutation = useMutation({
    mutationFn: () => proposeDestruction(client, versionIds ?? [], reason),
    onSuccess: async () => {
      toast(t('retention.propose.done'))
      await invalidateAfterDestruction(queryClient)
      close()
      onDone()
    },
  })

  function close() {
    setReason('')
    setAttempted(false)
    mutation.reset()
    onClose()
  }

  const missing = reason.trim() === ''
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (missing) return
    mutation.mutate()
  }

  return (
    <Dialog
      open={versionIds !== undefined}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('retention.propose.title')}</DialogTitle>
          <DialogDescription>{t('retention.propose.description', { count })}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && missing}
          />
          {mutation.isError && <InlineError error={mutation.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t('common.loading') : t('retention.propose.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function CandidateState({ candidate }: { candidate: DestructionCandidate }) {
  if (candidate.underLegalHold) {
    return <Badge variant="destructive">{t('retention.list.held')}</Badge>
  }
  if (candidate.pendingProposalId) {
    return <Badge variant="secondary">{t('retention.list.inProposal')}</Badge>
  }
  return <Badge variant="outline">{t('retention.list.due')}</Badge>
}

function DestructionListSection({ search }: { search: RetentionPageSearch }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const id = useId()
  const list = useQuery(destructionListQuery(client, {}))
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [proposing, setProposing] = useState<string[] | undefined>(undefined)

  const all = useMemo(() => list.data?.candidates ?? [], [list.data])
  const classes = useMemo(() => distinct(all.map((c) => c.classKey)), [all])
  const rules = useMemo(() => distinct(all.map((c) => c.retentionRule)), [all])
  const shown = filterCandidates(all, search)
  const selectable = shown.filter((c) => c.manageable)
  const chosen = selectable.filter((c) => selected.has(c.versionId)).map((c) => c.versionId)

  function filter(change: Partial<RetentionPageSearch>) {
    void navigate({ to: '/retention', search: { ...search, ...change }, replace: true })
  }

  function toggle(versionId: string, on: boolean) {
    const next = new Set(selected)
    if (on) next.add(versionId)
    else next.delete(versionId)
    setSelected(next)
  }

  const allChosen = selectable.length > 0 && chosen.length === selectable.length

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('retention.list.caption')}</h2>
        </CardTitle>
        {list.data && (list.data.withoutRule > 0 || list.data.retentionRunning > 0) && (
          <CardDescription>
            {list.data.withoutRule > 0 && (
              <span className="block">
                {t('retention.list.withoutRule', { count: list.data.withoutRule })}
              </span>
            )}
            {list.data.retentionRunning > 0 && (
              <span className="block">
                {t('retention.list.running', { count: list.data.retentionRunning })}
              </span>
            )}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        <div
          role="group"
          aria-label={t('retention.list.filters')}
          className="grid gap-3 sm:grid-cols-[12rem_12rem_1fr]"
        >
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-class`}>{t('retention.list.filterClass')}</Label>
            <select
              id={`${id}-class`}
              className={selectClass}
              value={search.class ?? ''}
              onChange={(e) => {
                filter({ class: e.target.value || undefined })
              }}
            >
              <option value="">{t('retention.list.all')}</option>
              {classes.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-rule`}>{t('retention.list.filterRule')}</Label>
            <select
              id={`${id}-rule`}
              className={selectClass}
              value={search.rule ?? ''}
              onChange={(e) => {
                filter({ rule: e.target.value || undefined })
              }}
            >
              <option value="">{t('retention.list.all')}</option>
              {rules.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-q`}>{t('retention.list.filterText')}</Label>
            <Input
              id={`${id}-q`}
              type="search"
              value={search.q ?? ''}
              onChange={(e) => {
                filter({ q: e.target.value || undefined })
              }}
            />
          </div>
        </div>
        {list.isPending ? (
          <LoadingState label={t('retention.list.loading')} />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : all.length === 0 ? (
          <EmptyState
            icon={Archive}
            title={t('retention.list.empty')}
            description={t('retention.list.emptyText')}
          />
        ) : shown.length === 0 ? (
          <p className="text-sm text-muted-foreground" role="status">
            {t('retention.list.noMatch')}
          </p>
        ) : (
          <>
            <Table
              caption={t('retention.list.caption')}
              head={[
                <input
                  key="all"
                  type="checkbox"
                  className="size-4"
                  aria-label={t('retention.list.selectAll')}
                  checked={allChosen}
                  disabled={selectable.length === 0}
                  onChange={(e) => {
                    setSelected(
                      e.target.checked
                        ? new Set(selectable.slice(0, MAX_PROPOSAL_ITEMS).map((c) => c.versionId))
                        : new Set(),
                    )
                  }}
                />,
                t('retention.list.colDocument'),
                t('retention.list.colVersion'),
                t('retention.list.colRule'),
                t('retention.list.colEnded'),
                t('retention.list.colState'),
              ]}
            >
              {shown.map((c) => (
                <tr key={c.versionId} className="border-b last:border-0">
                  <td className={td}>
                    <input
                      type="checkbox"
                      className="mt-0.5 size-4"
                      disabled={!c.manageable}
                      checked={selected.has(c.versionId)}
                      aria-label={t('retention.list.select', {
                        version: c.version,
                        title: c.title ?? c.documentNumber ?? c.documentId,
                      })}
                      onChange={(e) => {
                        toggle(c.versionId, e.target.checked)
                      }}
                    />
                  </td>
                  <td className={td}>
                    <DocumentTitle title={c.title} number={c.documentNumber} />
                    <span className="block text-xs text-muted-foreground">{c.classKey}</span>
                  </td>
                  <td className={`${td} tabular-nums`}>
                    {c.version}
                    <span className="block text-xs text-muted-foreground">
                      {t(`status.${c.status}` as 'status.Superseded')}
                    </span>
                  </td>
                  <td className={td}>
                    {c.retentionRuleName}
                    <span className="block font-mono text-xs text-muted-foreground">
                      {c.retentionRule}
                    </span>
                  </td>
                  <td className={`${td} whitespace-nowrap`}>{formatDate(c.retentionEndsOn)}</td>
                  <td className={td}>
                    <CandidateState candidate={c} />
                  </td>
                </tr>
              ))}
            </Table>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm text-muted-foreground" aria-live="polite">
                {t('retention.list.selected', { count: chosen.length })}
              </p>
              <Button
                disabled={chosen.length === 0}
                onClick={() => {
                  setProposing(chosen)
                }}
              >
                <Trash2 aria-hidden="true" />
                {t('retention.list.propose')}
              </Button>
            </div>
          </>
        )}
      </CardContent>
      <ProposeDialog
        versionIds={proposing}
        onClose={() => {
          setProposing(undefined)
        }}
        onDone={() => {
          setSelected(new Set())
          void navigate({ to: '/retention', search: { tab: 'proposals' } })
        }}
      />
    </Card>
  )
}

// Proposals

type Decision = 'approve' | 'reject'

function DecideDialog({
  proposal,
  decision,
  onClose,
}: {
  proposal: DestructionProposal
  decision: Decision | undefined
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const count = (proposal.items ?? []).length

  const mutation = useMutation({
    mutationFn: (d: Decision) => decideDestruction(client, proposal.id, d, reason),
    onSuccess: async (decided, d) => {
      if (d === 'approve') {
        const items = decided.items ?? []
        const destroyed = items.filter((i) => i.outcome === 'Destroyed').length
        const blocked = items.filter((i) => i.outcome === 'Blocked').length
        toast(
          t('retention.decide.approved', { count: destroyed }) +
            (blocked > 0 ? ` ${t('retention.decide.blocked', { count: blocked })}` : ''),
        )
      } else {
        toast(t('retention.decide.rejected'))
      }
      await invalidateAfterDestruction(queryClient)
      close()
    },
  })

  function close() {
    setReason('')
    setConfirmed(false)
    setAttempted(false)
    mutation.reset()
    onClose()
  }

  const missing = reason.trim() === ''
  const unconfirmed = decision === 'approve' && !confirmed
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (missing || unconfirmed || !decision) return
    mutation.mutate(decision)
  }

  return (
    <Dialog
      open={decision !== undefined}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {decision === 'approve'
              ? t('retention.decide.approveTitle')
              : t('retention.decide.rejectTitle')}
          </DialogTitle>
          <DialogDescription>
            {decision === 'approve'
              ? t('retention.decide.approveDescription', { count })
              : t('retention.decide.rejectDescription')}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && missing}
          />
          {decision === 'approve' && (
            <div className="grid gap-1">
              <div className="flex items-start gap-2">
                <input
                  id={`${id}-confirm`}
                  type="checkbox"
                  className="mt-1 size-4"
                  checked={confirmed}
                  aria-invalid={(attempted && unconfirmed) || undefined}
                  aria-describedby={attempted && unconfirmed ? `${id}-confirm-error` : undefined}
                  onChange={(e) => {
                    setConfirmed(e.target.checked)
                  }}
                />
                <Label htmlFor={`${id}-confirm`}>{t('retention.decide.confirm')}</Label>
              </div>
              {attempted && unconfirmed && (
                <p id={`${id}-confirm-error`} role="alert" className="text-sm text-destructive">
                  {t('retention.decide.confirmMissing')}
                </p>
              )}
            </div>
          )}
          {mutation.isError &&
            (isFourEyes(mutation.error) ? (
              <p role="alert" className="text-sm text-destructive">
                {t('retention.decide.fourEyes')}
              </p>
            ) : (
              <InlineError error={mutation.error} />
            ))}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button
              type="submit"
              variant={decision === 'approve' ? 'destructive' : 'default'}
              disabled={mutation.isPending}
            >
              {mutation.isPending
                ? t('common.loading')
                : decision === 'approve'
                  ? t('retention.decide.approveSubmit')
                  : t('retention.decide.rejectSubmit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function blockedText(detail: string | undefined): string {
  switch (detail) {
    case 'LegalHold':
    case 'RetentionRunning':
    case 'NoRule':
      return t(`retention.proposals.blocked.${detail}`)
    default:
      return t('retention.proposals.blocked.other', { detail: detail ?? '' })
  }
}

function ItemOutcome({ item }: { item: DestructionItem }) {
  const variant =
    item.outcome === 'Destroyed'
      ? 'secondary'
      : item.outcome === 'Blocked'
        ? 'destructive'
        : 'outline'
  const known = ['Pending', 'Destroyed', 'Blocked', 'Rejected'].includes(item.outcome)
  return (
    <div className="space-y-1">
      <Badge variant={variant}>
        {known ? t(`retention.proposals.outcome.${item.outcome as 'Pending'}`) : item.outcome}
      </Badge>
      {item.outcome === 'Blocked' && (
        <div className="text-xs text-muted-foreground">{blockedText(item.outcomeDetail)}</div>
      )}
      {item.outcome === 'Destroyed' && (
        <dl className="text-xs text-muted-foreground">
          {item.renditionSha256 && (
            <>
              <dt>{t('retention.proposals.rendition')}</dt>
              <dd className="font-mono break-all">{item.renditionSha256}</dd>
            </>
          )}
          {item.contentSha256 && (
            <>
              <dt>{t('retention.proposals.content')}</dt>
              <dd className="font-mono break-all">{item.contentSha256}</dd>
            </>
          )}
          {(item.files ?? []).length > 0 && (
            <>
              <dt>{t('retention.proposals.files')}</dt>
              {(item.files ?? []).map((f) => (
                <dd key={`${f.role}-${f.sha256}`}>
                  {f.released
                    ? t('retention.proposals.fileReleased', { name: f.name, role: f.role })
                    : t('retention.proposals.fileKept', { name: f.name, role: f.role })}
                </dd>
              ))}
            </>
          )}
        </dl>
      )}
    </div>
  )
}

function ProposalCard({ proposal }: { proposal: DestructionProposal }) {
  const [decision, setDecision] = useState<Decision | undefined>(undefined)
  const open = proposal.status === 'Proposed'
  const statusLabel = ['Proposed', 'Approved', 'Rejected'].includes(proposal.status)
    ? t(`retention.proposals.status.${proposal.status as 'Proposed'}`)
    : proposal.status
  return (
    <Card>
      <CardHeader className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-1">
          <CardTitle>
            <h3>
              {t('retention.proposals.heading', { date: formatDateTime(proposal.proposedAt) })}
            </h3>
          </CardTitle>
          <CardDescription>
            {t('retention.proposals.proposedBy')}: <UserName id={proposal.proposedBy} />
          </CardDescription>
        </div>
        <Badge variant={open ? 'default' : 'outline'}>{statusLabel}</Badge>
      </CardHeader>
      <CardContent className="space-y-4">
        <dl className="grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[12rem_1fr]">
          <dt className="text-muted-foreground">{t('retention.proposals.reason')}</dt>
          <dd>{proposal.reason}</dd>
          {proposal.decidedBy && (
            <>
              <dt className="text-muted-foreground">{t('retention.proposals.decidedBy')}</dt>
              <dd>
                <UserName id={proposal.decidedBy} />
                {proposal.decidedAt && `, ${formatDateTime(proposal.decidedAt)}`}
              </dd>
              <dt className="text-muted-foreground">{t('retention.proposals.decisionReason')}</dt>
              <dd>{proposal.decisionReason}</dd>
            </>
          )}
        </dl>
        <Table
          caption={t('retention.proposals.itemsCaption')}
          head={[
            t('retention.proposals.colDocument'),
            t('retention.proposals.colVersion'),
            t('retention.proposals.colRule'),
            t('retention.proposals.colOutcome'),
          ]}
        >
          {(proposal.items ?? []).map((i) => (
            <tr key={i.versionId} className="border-b last:border-0">
              <td className={td}>
                <DocumentTitle title={i.title} number={i.documentNumber} />
              </td>
              <td className={`${td} tabular-nums`}>{i.version}</td>
              <td className={td}>
                {t('retention.proposals.ended', {
                  rule: i.retentionRule,
                  date: formatDate(i.retentionEndedOn),
                })}
              </td>
              <td className={td}>
                <ItemOutcome item={i} />
              </td>
            </tr>
          ))}
        </Table>
        {open &&
          (proposal.canDecide ? (
            <div className="flex flex-wrap justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setDecision('reject')
                }}
              >
                {t('retention.proposals.reject')}
              </Button>
              <Button
                variant="destructive"
                onClick={() => {
                  setDecision('approve')
                }}
              >
                {t('retention.proposals.approve')}
              </Button>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t('retention.proposals.needsSecond')}</p>
          ))}
      </CardContent>
      <DecideDialog
        proposal={proposal}
        decision={decision}
        onClose={() => {
          setDecision(undefined)
        }}
      />
    </Card>
  )
}

const PROPOSAL_STATUSES: readonly ProposalStatus[] = ['proposed', 'approved', 'rejected', 'all']

function ProposalsSection({ search }: { search: RetentionPageSearch }) {
  const client = useApiClient()
  const status: ProposalStatus = search.status ?? 'proposed'
  const list = useQuery(proposalsQuery(client, status))
  return (
    <section aria-labelledby="retention-proposals" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="retention-proposals" className="text-lg font-semibold">
          {t('retention.proposals.caption')}
        </h2>
        <div role="group" aria-label={t('retention.proposals.filter')} className="flex gap-1">
          {PROPOSAL_STATUSES.map((s) => (
            <Button key={s} asChild variant={s === status ? 'secondary' : 'ghost'} size="sm">
              <Link
                to="/retention"
                search={s === 'proposed' ? { tab: 'proposals' } : { tab: 'proposals', status: s }}
                aria-current={s === status ? 'true' : undefined}
              >
                {t(`retention.proposals.statuses.${s}`)}
              </Link>
            </Button>
          ))}
        </div>
      </div>
      {list.isPending ? (
        <LoadingState label={t('retention.proposals.loading')} />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <EmptyState icon={Archive} title={t('retention.proposals.empty')} />
      ) : (
        list.data.map((p) => <ProposalCard key={p.id} proposal={p} />)
      )}
    </section>
  )
}

// Rules

interface RuleDraft {
  key: string
  name: string
  periodMonths: string
  trigger: RetentionTrigger
  fromYearEnd: boolean
}

const emptyRule: RuleDraft = {
  key: '',
  name: '',
  periodMonths: '120',
  trigger: 'EndOfValidity',
  fromYearEnd: true,
}

function draftOf(rule: RetentionRule): RuleDraft {
  return {
    key: rule.key,
    name: rule.name,
    periodMonths: String(rule.periodMonths),
    trigger: RETENTION_TRIGGERS.find((tr) => tr === rule.trigger) ?? 'EndOfValidity',
    fromYearEnd: rule.fromYearEnd,
  }
}

function triggerLabel(trigger: string): string {
  return RETENTION_TRIGGERS.find((tr) => tr === trigger)
    ? t(`retention.rules.trigger.${trigger as RetentionTrigger}`)
    : trigger
}

function RuleDialog({
  rule,
  editing,
  onClose,
}: {
  rule: RuleDraft | undefined
  editing: boolean
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [draft, setDraft] = useState<RuleDraft>(rule ?? emptyRule)
  const [attempted, setAttempted] = useState(false)

  const months = Number(draft.periodMonths)
  const keyInvalid = !RULE_KEY.test(draft.key)
  const nameMissing = draft.name.trim() === ''
  const periodInvalid = !Number.isInteger(months) || months < 1 || months > MAX_PERIOD_MONTHS

  const mutation = useMutation({
    mutationFn: () =>
      saveRetentionRule(client, draft.key, {
        name: draft.name,
        periodMonths: months,
        trigger: draft.trigger,
        fromYearEnd: draft.fromYearEnd,
      }),
    onSuccess: async (saved) => {
      toast(t('retention.rules.form.done', { key: saved.key }))
      await queryClient.invalidateQueries({ queryKey: ['retention'] })
      onClose()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (keyInvalid || nameMissing || periodInvalid) return
    mutation.mutate()
  }

  function field<K extends keyof RuleDraft>(key: K, value: RuleDraft[K]) {
    setDraft({ ...draft, [key]: value })
  }

  return (
    <Dialog
      open={rule !== undefined}
      onOpenChange={(o) => {
        if (!o) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {editing
              ? t('retention.rules.form.editTitle', { key: draft.key })
              : t('retention.rules.form.title')}
          </DialogTitle>
          <DialogDescription>{t('retention.rules.form.description')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          {!editing && (
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-key`}>{t('retention.rules.form.key')}</Label>
              <Input
                id={`${id}-key`}
                value={draft.key}
                maxLength={63}
                aria-required
                aria-invalid={(attempted && keyInvalid) || undefined}
                aria-describedby={`${id}-key-hint`}
                onChange={(e) => {
                  field('key', e.target.value.trim())
                }}
              />
              <p
                id={`${id}-key-hint`}
                role={attempted && keyInvalid ? 'alert' : undefined}
                className={
                  attempted && keyInvalid
                    ? 'text-sm text-destructive'
                    : 'text-xs text-muted-foreground'
                }
              >
                {attempted && keyInvalid
                  ? t('retention.rules.form.keyInvalid')
                  : t('retention.rules.form.keyHint')}
              </p>
            </div>
          )}
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-name`}>{t('retention.rules.form.name')}</Label>
            <Input
              id={`${id}-name`}
              value={draft.name}
              maxLength={200}
              aria-required
              aria-invalid={(attempted && nameMissing) || undefined}
              aria-describedby={attempted && nameMissing ? `${id}-name-error` : undefined}
              onChange={(e) => {
                field('name', e.target.value)
              }}
            />
            {attempted && nameMissing && (
              <p id={`${id}-name-error`} role="alert" className="text-sm text-destructive">
                {t('retention.rules.form.nameMissing')}
              </p>
            )}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-period`}>{t('retention.rules.form.period')}</Label>
              <Input
                id={`${id}-period`}
                type="number"
                inputMode="numeric"
                min={1}
                max={MAX_PERIOD_MONTHS}
                value={draft.periodMonths}
                aria-required
                aria-invalid={(attempted && periodInvalid) || undefined}
                aria-describedby={attempted && periodInvalid ? `${id}-period-error` : undefined}
                onChange={(e) => {
                  field('periodMonths', e.target.value)
                }}
              />
              {attempted && periodInvalid && (
                <p id={`${id}-period-error`} role="alert" className="text-sm text-destructive">
                  {t('retention.rules.form.periodInvalid', { max: MAX_PERIOD_MONTHS })}
                </p>
              )}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-trigger`}>{t('retention.rules.form.trigger')}</Label>
              <select
                id={`${id}-trigger`}
                className={selectClass}
                value={draft.trigger}
                onChange={(e) => {
                  field(
                    'trigger',
                    RETENTION_TRIGGERS.find((tr) => tr === e.target.value) ?? 'EndOfValidity',
                  )
                }}
              >
                {RETENTION_TRIGGERS.map((tr) => (
                  <option key={tr} value={tr}>
                    {t(`retention.rules.trigger.${tr}`)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <input
              id={`${id}-year-end`}
              type="checkbox"
              className="size-4"
              checked={draft.fromYearEnd}
              onChange={(e) => {
                field('fromYearEnd', e.target.checked)
              }}
            />
            <Label htmlFor={`${id}-year-end`}>{t('retention.rules.form.fromYearEnd')}</Label>
          </div>
          {mutation.isError && <InlineError error={mutation.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t('common.loading') : t('retention.rules.form.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function RulesSection() {
  const client = useApiClient()
  const rules = useQuery(retentionRulesQuery(client))
  const [editing, setEditing] = useState<{ draft: RuleDraft; existing: boolean } | undefined>()
  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle>
          <h2>{t('retention.rules.caption')}</h2>
        </CardTitle>
        <Button
          onClick={() => {
            setEditing({ draft: emptyRule, existing: false })
          }}
        >
          <Plus aria-hidden="true" />
          {t('retention.rules.newRule')}
        </Button>
      </CardHeader>
      <CardContent>
        {rules.isPending ? (
          <LoadingState label={t('retention.rules.loading')} />
        ) : rules.isError ? (
          <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />
        ) : rules.data.length === 0 ? (
          <EmptyState
            icon={Archive}
            title={t('retention.rules.empty')}
            description={t('retention.rules.emptyText')}
          />
        ) : (
          <Table
            caption={t('retention.rules.caption')}
            head={[
              t('retention.rules.colKey'),
              t('retention.rules.colName'),
              t('retention.rules.colPeriod'),
              t('retention.rules.colTrigger'),
              t('retention.rules.colYearEnd'),
              '',
            ]}
          >
            {rules.data.map((r) => (
              <tr key={r.key} className="border-b last:border-0">
                <td className={`${td} font-mono`}>{r.key}</td>
                <td className={td}>{r.name}</td>
                <td className={`${td} tabular-nums`}>
                  {t('retention.rules.months', { count: r.periodMonths })}
                </td>
                <td className={td}>{triggerLabel(r.trigger)}</td>
                <td className={td}>
                  {r.fromYearEnd ? t('retention.rules.yes') : t('retention.rules.no')}
                </td>
                <td className="py-2 align-top">
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={t('retention.rules.editLabel', { key: r.key })}
                    onClick={() => {
                      setEditing({ draft: draftOf(r), existing: true })
                    }}
                  >
                    {t('retention.rules.edit')}
                  </Button>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </CardContent>
      {editing && (
        <RuleDialog
          key={editing.draft.key || 'new'}
          rule={editing.draft}
          editing={editing.existing}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      )}
    </Card>
  )
}

// Storage clean-up

function summaryOf(report: CleanupReport): string {
  const s = report.summary
  const values = {
    scanned: s.scanned,
    orphaned: s.orphaned,
    deleted: s.deleted,
    kept: s.keptLocked + s.keptHeld + s.keptRecent,
    files: s.filesDeleted,
  }
  return report.dryRun
    ? t('retention.cleanup.summaryDry', values)
    : t('retention.cleanup.summary', values)
}

const OUTCOMES = [
  'Deleted',
  'WouldDelete',
  'KeptLocked',
  'KeptHeld',
  'KeptRecent',
  'Failed',
  'FileDeleted',
  'FileWouldDelete',
] as const

function outcomeLabel(outcome: string): string {
  const known = OUTCOMES.find((o) => o === outcome)
  return known ? t(`retention.cleanup.outcome.${known}`) : outcome
}

function CleanupSection() {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const runs = useQuery(cleanupRunsQuery(client))
  const [confirming, setConfirming] = useState(false)
  const mutation = useMutation({
    mutationFn: (dryRun: boolean) => runStorageCleanup(client, dryRun),
    onSuccess: async (report) => {
      toast(report.dryRun ? t('retention.cleanup.checked') : t('retention.cleanup.done'))
      setConfirming(false)
      await queryClient.invalidateQueries({ queryKey: ['retention', 'cleanup'] })
    },
  })
  const latest = mutation.data ?? runs.data?.[0]
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>{t('retention.tabs.cleanup')}</h2>
        </CardTitle>
        <CardDescription>{t('retention.cleanup.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            disabled={mutation.isPending}
            onClick={() => {
              mutation.mutate(true)
            }}
          >
            {t('retention.cleanup.dryRun')}
          </Button>
          <Button
            variant="destructive"
            disabled={mutation.isPending}
            onClick={() => {
              setConfirming(true)
            }}
          >
            {t('retention.cleanup.run')}
          </Button>
          {mutation.isPending && (
            <span role="status" className="self-center text-sm text-muted-foreground">
              {t('retention.cleanup.running')}
            </span>
          )}
        </div>
        {mutation.isError && !confirming && <InlineError error={mutation.error} />}
        {runs.isPending ? (
          <LoadingState label={t('retention.cleanup.loading')} />
        ) : runs.isError ? (
          <ErrorState error={runs.error} onRetry={() => void runs.refetch()} />
        ) : !latest ? (
          <EmptyState icon={Archive} title={t('retention.cleanup.empty')} />
        ) : (
          <>
            <p className="text-sm" role="status" data-testid="cleanup-summary">
              {summaryOf(latest)}
            </p>
            {(latest.entries ?? []).length > 0 && (
              <Table
                caption={t('retention.cleanup.entriesCaption')}
                head={[
                  t('retention.cleanup.colObject'),
                  t('retention.cleanup.colOutcome'),
                  t('retention.cleanup.colDetail'),
                ]}
              >
                {(latest.entries ?? []).map((e) => (
                  <tr key={`${e.key}-${e.version}-${e.outcome}`} className="border-b last:border-0">
                    <td className={`${td} font-mono text-xs break-all`}>{e.key}</td>
                    <td className={td}>{outcomeLabel(e.outcome)}</td>
                    <td className={`${td} text-xs text-muted-foreground`}>{e.detail ?? ''}</td>
                  </tr>
                ))}
              </Table>
            )}
            {runs.data.length > 0 && (
              <Table
                caption={t('retention.cleanup.runsCaption')}
                head={[
                  t('retention.cleanup.colStarted'),
                  t('retention.cleanup.colKind'),
                  t('retention.cleanup.colBy'),
                  t('retention.cleanup.colResult'),
                ]}
              >
                {runs.data.map((r) => (
                  <tr key={r.id} className="border-b last:border-0">
                    <td className={`${td} whitespace-nowrap`}>{formatDateTime(r.startedAt)}</td>
                    <td className={td}>
                      {r.dryRun
                        ? t('retention.cleanup.kind.dry')
                        : t('retention.cleanup.kind.real')}
                      <span className="block text-xs text-muted-foreground">
                        {t('retention.cleanup.graceHours', { count: r.graceHours })}
                      </span>
                    </td>
                    <td className={td}>
                      <UserName id={r.triggeredBy} />
                    </td>
                    <td className={`${td} text-xs`}>{summaryOf(r)}</td>
                  </tr>
                ))}
              </Table>
            )}
          </>
        )}
      </CardContent>
      <Dialog
        open={confirming}
        onOpenChange={(o) => {
          if (!o) setConfirming(false)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('retention.cleanup.runTitle')}</DialogTitle>
            <DialogDescription>{t('retention.cleanup.runDescription')}</DialogDescription>
          </DialogHeader>
          {mutation.isError && <InlineError error={mutation.error} />}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setConfirming(false)
              }}
            >
              {t('common.cancel')}
            </Button>
            <Button
              variant="destructive"
              disabled={mutation.isPending}
              onClick={() => {
                mutation.mutate(false)
              }}
            >
              {mutation.isPending ? t('common.loading') : t('retention.cleanup.runSubmit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

/**
 * Retention for records managers (business concept 7.4, DMS-82, ADR 0054): the destruction list,
 * proposals decided with four eyes, retention rules and the clean-up of object storage.
 */
export function RetentionPage() {
  const search = useSearch({ from: '/_app/retention' })
  const me = useMe()
  const allowed = canManageRetention(me.data)
  const tab: Tab = search.tab ?? 'list'

  return (
    <div className="space-y-6">
      <PageHeader title={t('retention.title')} description={t('retention.description')} />
      {me.isPending ? (
        <LoadingState label={t('retention.list.loading')} />
      ) : me.isError ? (
        <ErrorState error={me.error} onRetry={() => void me.refetch()} />
      ) : !allowed ? (
        <EmptyState
          icon={Archive}
          title={t('retention.notAllowedTitle')}
          description={t('retention.notAllowedText')}
        />
      ) : (
        <>
          <nav aria-label={t('retention.sections')} className="flex flex-wrap gap-1 border-b pb-2">
            {TABS.map((s) => (
              <Button key={s} asChild variant={s === tab ? 'secondary' : 'ghost'} size="sm">
                <Link
                  to="/retention"
                  search={s === 'list' ? {} : { tab: s }}
                  aria-current={s === tab ? 'page' : undefined}
                >
                  {t(`retention.tabs.${s}`)}
                </Link>
              </Button>
            ))}
          </nav>
          {tab === 'list' && <DestructionListSection search={search} />}
          {tab === 'proposals' && <ProposalsSection search={search} />}
          {tab === 'rules' && <RulesSection />}
          {tab === 'cleanup' && <CleanupSection />}
        </>
      )}
    </div>
  )
}
