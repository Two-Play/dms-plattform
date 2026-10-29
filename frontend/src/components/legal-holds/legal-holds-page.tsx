import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { ArrowLeft, Plus, Scale, ShieldOff } from 'lucide-react'
import { useId, useState, type ReactNode, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  addLegalHoldDocuments,
  confirmable,
  isResultChanged,
  canManageLegalHolds,
  confirmedFilter,
  createLegalHold,
  documentIdsIn,
  invalidateAfterHoldChange,
  legalHoldQuery,
  legalHoldsQuery,
  MAX_HOLD_DOCUMENTS,
  previewHoldSearch,
  releaseLegalHold,
  removeLegalHoldDocument,
  type HeldDocument,
  type HoldSearch,
  type HoldSearchPreview,
  type LegalHold,
  type LegalHoldStatus,
} from '@/api/legal-holds'
import { useMe } from '@/api/me'
import { ErrorState } from '@/components/shared/error-state'
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
import { HoldError, MAX_NAME, ReasonField, SearchPreview } from './hold-forms'
import type { LegalHoldsPageSearch } from './legal-holds-params'

const th = 'py-2 pr-4 font-medium'
const td = 'py-2 pr-4 align-top'
const linkClass = 'font-medium underline-offset-4 hover:underline'

function HoldStatusBadge({ status }: { status: string }) {
  return (
    <Badge variant={status === 'Active' ? 'destructive' : 'outline'}>
      {status === 'Active' || status === 'Released' ? t(`legalHolds.status.${status}`) : status}
    </Badge>
  )
}

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
            {head.map((h, i) => (
              <th key={`${h}-${String(i)}`} scope="col" className={th}>
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

const STATUSES: readonly LegalHoldStatus[] = ['active', 'released', 'all']

function statusOf(search: LegalHoldsPageSearch): LegalHoldStatus {
  return search.status ?? 'active'
}

/** Active, released or all holds; a hold opens its detail. */
function HoldList({ search }: { search: LegalHoldsPageSearch }) {
  const client = useApiClient()
  const status = statusOf(search)
  const list = useQuery(legalHoldsQuery(client, status))
  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <CardTitle>
          <h2>{t('legalHolds.listCaption')}</h2>
        </CardTitle>
        <div role="group" aria-label={t('legalHolds.filter')} className="flex gap-1">
          {STATUSES.map((s) => (
            <Button key={s} asChild variant={s === status ? 'secondary' : 'ghost'} size="sm">
              <Link
                to="/legal-holds"
                search={s === 'active' ? {} : { status: s }}
                aria-current={s === status ? 'true' : undefined}
              >
                {t(`legalHolds.statuses.${s}`)}
              </Link>
            </Button>
          ))}
        </div>
      </CardHeader>
      <CardContent>
        {list.isPending ? (
          <LoadingState label={t('legalHolds.loading')} />
        ) : list.isError ? (
          <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        ) : list.data.length === 0 ? (
          <EmptyState
            icon={Scale}
            title={
              status === 'active'
                ? t('legalHolds.emptyActive')
                : status === 'released'
                  ? t('legalHolds.emptyReleased')
                  : t('legalHolds.emptyAll')
            }
            description={status === 'released' ? undefined : t('legalHolds.emptyText')}
          />
        ) : (
          <Table
            caption={t('legalHolds.listCaption')}
            head={[
              t('legalHolds.colName'),
              t('legalHolds.colMatter'),
              t('legalHolds.colDocuments'),
              t('legalHolds.colCreated'),
              t('legalHolds.colStatus'),
            ]}
          >
            {list.data.map((h) => (
              <tr key={h.id} className="border-b last:border-0">
                <td className={td}>
                  <Link to="/legal-holds" search={{ ...search, hold: h.id }} className={linkClass}>
                    {h.name}
                  </Link>
                </td>
                <td className={td}>{h.matterReference ?? '—'}</td>
                <td className={`${td} tabular-nums`}>{h.documentCount}</td>
                <td className={`${td} whitespace-nowrap`}>
                  {formatDate(h.createdAt)}
                  <div className="text-xs text-muted-foreground">
                    <UserName id={h.createdBy} />
                  </div>
                </td>
                <td className={td}>
                  <HoldStatusBadge status={h.status} />
                </td>
              </tr>
            ))}
          </Table>
        )}
      </CardContent>
    </Card>
  )
}

/** Name, matter reference and reason of a new hold; opens the new hold. */
function CreateHoldDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const id = useId()
  const [name, setName] = useState('')
  const [matter, setMatter] = useState('')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)

  const mutation = useMutation({
    mutationFn: () =>
      createLegalHold(client, { name, reason, matterReference: matter || undefined }),
    onSuccess: async (hold) => {
      toast(t('legalHolds.create.done', { name: hold.name }))
      await invalidateAfterHoldChange(queryClient)
      close()
      void navigate({ to: '/legal-holds', search: { hold: hold.id } })
    },
  })

  function close() {
    setName('')
    setMatter('')
    setReason('')
    setAttempted(false)
    mutation.reset()
    onClose()
  }

  const nameMissing = name.trim() === ''
  const reasonMissing = reason.trim() === ''
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (nameMissing || reasonMissing) return
    mutation.mutate()
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('legalHolds.create.title')}</DialogTitle>
          <DialogDescription>{t('legalHolds.create.description')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-name`}>{t('legalHolds.create.name')}</Label>
            <Input
              id={`${id}-name`}
              value={name}
              maxLength={MAX_NAME}
              aria-required
              aria-invalid={(attempted && nameMissing) || undefined}
              aria-describedby={attempted && nameMissing ? `${id}-name-error` : undefined}
              onChange={(e) => {
                setName(e.target.value)
              }}
            />
            {attempted && nameMissing && (
              <p id={`${id}-name-error`} role="alert" className="text-sm text-destructive">
                {t('legalHolds.create.nameMissing')}
              </p>
            )}
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor={`${id}-matter`}>{t('legalHolds.create.matter')}</Label>
            <Input
              id={`${id}-matter`}
              value={matter}
              maxLength={MAX_NAME}
              aria-describedby={`${id}-matter-hint`}
              onChange={(e) => {
                setMatter(e.target.value)
              }}
            />
            <p id={`${id}-matter-hint`} className="text-xs text-muted-foreground">
              {t('legalHolds.create.matterHint')}
            </p>
          </div>
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && reasonMissing}
          />
          {mutation.isError && <HoldError error={mutation.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t('common.loading') : t('legalHolds.create.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AddMode = 'search' | 'named'

/** The search fields of the add dialog; any change discards the reviewed preview. */
function SearchFields({
  id,
  search,
  onChange,
}: {
  id: string
  search: HoldSearch
  onChange: (search: HoldSearch) => void
}) {
  return (
    <div className="grid gap-3 sm:grid-cols-[1fr_10rem_6rem]">
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-q`}>{t('legalHolds.search.text')}</Label>
        <Input
          id={`${id}-q`}
          type="search"
          value={search.q ?? ''}
          aria-describedby={`${id}-q-hint`}
          onChange={(e) => {
            onChange({ ...search, q: e.target.value })
          }}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-class`}>{t('legalHolds.search.class')}</Label>
        <Input
          id={`${id}-class`}
          value={search.class ?? ''}
          onChange={(e) => {
            onChange({ ...search, class: e.target.value.trim() || undefined })
          }}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-lang`}>{t('legalHolds.search.lang')}</Label>
        <Input
          id={`${id}-lang`}
          value={search.lang ?? ''}
          maxLength={8}
          onChange={(e) => {
            onChange({ ...search, lang: e.target.value.trim() || undefined })
          }}
        />
      </div>
      <p id={`${id}-q-hint`} className="text-xs text-muted-foreground sm:col-span-3">
        {t('legalHolds.search.textHint')}
      </p>
    </div>
  )
}

function hasCriteria(search: HoldSearch): boolean {
  return !!search.q?.trim() || !!search.class
}

/**
 * Adds documents to an active hold: the documents of a search the records manager checked (the
 * request confirms exactly the reviewed documents) or named documents (pasted links or ids).
 */
function AddDocumentsDialog({
  hold,
  open,
  onClose,
}: {
  hold: LegalHold
  open: boolean
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [mode, setMode] = useState<AddMode>('search')
  const [search, setSearch] = useState<HoldSearch>({})
  const [preview, setPreview] = useState<HoldSearchPreview | undefined>(undefined)
  const [named, setNamed] = useState('')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const ids = documentIdsIn(named)

  const check = useMutation({
    mutationFn: () => previewHoldSearch(client, search),
    onSuccess: setPreview,
  })
  const add = useMutation({
    mutationFn: () =>
      addLegalHoldDocuments(
        client,
        hold.id,
        mode === 'search' && preview
          ? { reason, searchFilter: confirmedFilter(search, preview) }
          : { reason, documentIds: ids },
      ),
    onSuccess: async (changed) => {
      toast(t('legalHolds.add.done', { count: changed.addedDocumentIds?.length ?? 0 }))
      await invalidateAfterHoldChange(queryClient)
      close()
    },
    onError: (error) => {
      // The reviewed documents are no longer what the search finds: check again.
      if (isResultChanged(error)) setPreview(undefined)
    },
  })

  function close() {
    setMode('search')
    setSearch({})
    setPreview(undefined)
    setNamed('')
    setReason('')
    setAttempted(false)
    check.reset()
    add.reset()
    onClose()
  }

  function changeSearch(next: HoldSearch) {
    setSearch(next)
    setPreview(undefined)
    check.reset()
  }

  const reasonMissing = reason.trim() === ''
  const namedMissing = ids.length === 0
  const tooMany = ids.length > MAX_HOLD_DOCUMENTS
  const ready = mode === 'search' ? confirmable(preview) : !namedMissing && !tooMany

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing || !ready) return
    add.mutate()
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('legalHolds.add.title', { name: hold.name })}</DialogTitle>
          <DialogDescription>{t('legalHolds.add.description')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <fieldset className="flex flex-wrap gap-4">
            <legend className="mb-1 w-full text-sm font-medium">{t('legalHolds.add.mode')}</legend>
            {(
              [
                ['search', t('legalHolds.add.fromSearch')],
                ['named', t('legalHolds.add.named')],
              ] as const
            ).map(([value, label]) => (
              <label key={value} className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  name={`${id}-mode`}
                  value={value}
                  checked={mode === value}
                  onChange={() => {
                    setMode(value)
                    add.reset()
                  }}
                />
                {label}
              </label>
            ))}
          </fieldset>
          {mode === 'search' ? (
            <div className="grid gap-3">
              <SearchFields id={id} search={search} onChange={changeSearch} />
              <div>
                <Button
                  type="button"
                  variant="outline"
                  disabled={!hasCriteria(search) || check.isPending}
                  onClick={() => {
                    check.mutate()
                  }}
                >
                  {check.isPending ? t('legalHolds.search.checking') : t('legalHolds.search.check')}
                </Button>
              </div>
              {check.isError && <HoldError error={check.error} />}
              {preview && <SearchPreview preview={preview} />}
              {attempted && !preview && !check.isPending && (
                <p role="alert" className="text-sm text-destructive">
                  {hasCriteria(search)
                    ? t('legalHolds.search.changed')
                    : t('legalHolds.search.required')}
                </p>
              )}
            </div>
          ) : (
            <div className="grid gap-1.5">
              <Label htmlFor={`${id}-ids`}>{t('legalHolds.add.ids')}</Label>
              <Textarea
                id={`${id}-ids`}
                value={named}
                rows={4}
                aria-required
                aria-invalid={(attempted && (namedMissing || tooMany)) || undefined}
                aria-describedby={`${id}-ids-hint`}
                onChange={(e) => {
                  setNamed(e.target.value)
                }}
              />
              <p
                id={`${id}-ids-hint`}
                role={attempted && (namedMissing || tooMany) ? 'alert' : undefined}
                className={
                  attempted && (namedMissing || tooMany)
                    ? 'text-sm text-destructive'
                    : 'text-xs text-muted-foreground'
                }
              >
                {attempted && namedMissing
                  ? t('legalHolds.add.idsMissing')
                  : tooMany
                    ? t('legalHolds.add.tooMany', { max: MAX_HOLD_DOCUMENTS })
                    : ids.length > 0
                      ? t('legalHolds.add.idsFound', { count: ids.length })
                      : t('legalHolds.add.idsHint')}
              </p>
            </div>
          )}
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && reasonMissing}
          />
          {add.isError && <HoldError error={add.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={add.isPending || (mode === 'search' && !ready)}>
              {add.isPending
                ? t('common.loading')
                : mode === 'search'
                  ? t('legalHolds.search.submit', { count: preview?.documents?.length ?? 0 })
                  : t('legalHolds.add.submitNamed', { count: ids.length })}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** Takes one document out of an active hold, with a reason. */
function RemoveDocumentDialog({
  hold,
  document,
  onClose,
}: {
  hold: LegalHold
  document: HeldDocument | undefined
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: (d: HeldDocument) => removeLegalHoldDocument(client, hold.id, d.documentId, reason),
    onSuccess: async () => {
      toast(t('legalHolds.removeDialog.done'))
      await invalidateAfterHoldChange(queryClient)
      close()
    },
  })

  function close() {
    setReason('')
    setAttempted(false)
    mutation.reset()
    onClose()
  }

  const reasonMissing = reason.trim() === ''
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing || !document) return
    mutation.mutate(document)
  }

  return (
    <Dialog
      open={document !== undefined}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      {document && (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t('legalHolds.removeDialog.title', {
                title: document.title ?? t('legalHolds.hiddenTitle'),
              })}
            </DialogTitle>
            <DialogDescription>{t('legalHolds.removeDialog.description')}</DialogDescription>
          </DialogHeader>
          <form onSubmit={submit} className="grid gap-4" noValidate>
            <ReasonField
              id={`${id}-reason`}
              value={reason}
              onChange={setReason}
              invalid={attempted && reasonMissing}
            />
            {mutation.isError && <HoldError error={mutation.error} />}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={close}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" variant="destructive" disabled={mutation.isPending}>
                {mutation.isPending ? t('common.loading') : t('legalHolds.removeDialog.submit')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      )}
    </Dialog>
  )
}

/**
 * Releases a hold: the backend needs a reason (no second person); the records manager also
 * confirms explicitly that the matter is closed, since a release cannot be undone.
 */
function ReleaseDialog({
  hold,
  open,
  onClose,
}: {
  hold: LegalHold
  open: boolean
  onClose: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [attempted, setAttempted] = useState(false)
  const mutation = useMutation({
    mutationFn: () => releaseLegalHold(client, hold.id, reason),
    onSuccess: async (released) => {
      toast(t('legalHolds.releaseDialog.done', { name: released.name }))
      await invalidateAfterHoldChange(queryClient)
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

  const reasonMissing = reason.trim() === ''
  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing || !confirmed) return
    mutation.mutate()
  }

  const confirmError = attempted && !confirmed
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) close()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('legalHolds.releaseDialog.title', { name: hold.name })}</DialogTitle>
          <DialogDescription>
            {t('legalHolds.releaseDialog.description', { count: hold.documentCount })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="grid gap-4" noValidate>
          <ReasonField
            id={`${id}-reason`}
            value={reason}
            onChange={setReason}
            invalid={attempted && reasonMissing}
          />
          <div className="grid gap-1">
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-0.5"
                checked={confirmed}
                aria-invalid={confirmError || undefined}
                aria-describedby={confirmError ? `${id}-confirm-error` : undefined}
                onChange={(e) => {
                  setConfirmed(e.target.checked)
                }}
              />
              {t('legalHolds.releaseDialog.confirm')}
            </label>
            {confirmError && (
              <p id={`${id}-confirm-error`} role="alert" className="text-sm text-destructive">
                {t('legalHolds.releaseDialog.confirmMissing')}
              </p>
            )}
          </div>
          {mutation.isError && <HoldError error={mutation.error} />}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="destructive" disabled={mutation.isPending}>
              {mutation.isPending ? t('common.loading') : t('legalHolds.releaseDialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  )
}

/** One hold: what it is for, its documents with their history, and the actions while it is active. */
function HoldDetail({ holdId, search }: { holdId: string; search: LegalHoldsPageSearch }) {
  const client = useApiClient()
  const hold = useQuery(legalHoldQuery(client, holdId))
  const [adding, setAdding] = useState(false)
  const [releasing, setReleasing] = useState(false)
  const [removing, setRemoving] = useState<HeldDocument | undefined>(undefined)
  const back = { ...search, hold: undefined }

  const backLink = (
    <Link
      to="/legal-holds"
      search={back}
      className="inline-flex items-center gap-1 text-sm underline-offset-4 hover:underline"
    >
      <ArrowLeft aria-hidden="true" className="size-4" />
      {t('legalHolds.back')}
    </Link>
  )

  if (hold.isPending) {
    return (
      <div className="space-y-3">
        {backLink}
        <LoadingState label={t('legalHolds.loadingHold')} rows={4} />
      </div>
    )
  }
  if (hold.isError) {
    return (
      <div className="space-y-3">
        {backLink}
        <ErrorState
          error={hold.error}
          titles={{ notFound: t('legalHolds.notFound') }}
          onRetry={() => void hold.refetch()}
        />
      </div>
    )
  }
  const h = hold.data
  const active = h.status === 'Active'
  const documents = h.documents ?? []
  return (
    <div className="space-y-4">
      {backLink}
      <Card>
        <CardHeader className="flex flex-wrap items-start justify-between gap-3">
          <div className="space-y-1">
            <CardTitle className="flex flex-wrap items-center gap-2">
              <h2>{h.name}</h2>
              <HoldStatusBadge status={h.status} />
            </CardTitle>
            <CardDescription>
              {t('legalHolds.documentCount', { count: h.documentCount })}
            </CardDescription>
          </div>
          {active && (
            <div className="flex flex-wrap gap-2">
              <Button
                onClick={() => {
                  setAdding(true)
                }}
              >
                <Plus aria-hidden="true" />
                {t('legalHolds.addDocuments')}
              </Button>
              <Button
                variant="outline"
                onClick={() => {
                  setReleasing(true)
                }}
              >
                <ShieldOff aria-hidden="true" />
                {t('legalHolds.release')}
              </Button>
            </div>
          )}
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {h.matterReference && <Fact label={t('legalHolds.matter')}>{h.matterReference}</Fact>}
            <Fact label={t('legalHolds.reason')}>{h.reason}</Fact>
            <Fact label={t('legalHolds.setBy')}>
              <UserName id={h.createdBy} />
            </Fact>
            <Fact label={t('legalHolds.setAt')}>{formatDateTime(h.createdAt)}</Fact>
            {h.releasedAt && (
              <>
                <Fact label={t('legalHolds.releasedBy')}>
                  {h.releasedBy ? <UserName id={h.releasedBy} /> : '—'}
                </Fact>
                <Fact label={t('legalHolds.releasedAt')}>{formatDateTime(h.releasedAt)}</Fact>
                <Fact label={t('legalHolds.releaseReason')}>{h.releaseReason ?? '—'}</Fact>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>
            <h3>{t('legalHolds.documents')}</h3>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {documents.length === 0 ? (
            <EmptyState
              icon={Scale}
              title={t('legalHolds.noDocuments')}
              description={active ? t('legalHolds.noDocumentsText') : undefined}
            />
          ) : (
            <Table
              caption={t('legalHolds.documentsCaption')}
              head={[
                t('legalHolds.colDocument'),
                t('legalHolds.colAdded'),
                t('legalHolds.colReason'),
                t('legalHolds.colState'),
                '',
              ]}
            >
              {documents.map((d, i) => (
                <DocumentRow
                  key={`${d.documentId}-${String(i)}`}
                  document={d}
                  removable={active && !d.removedAt && d.title !== undefined}
                  onRemove={() => {
                    setRemoving(d)
                  }}
                />
              ))}
            </Table>
          )}
        </CardContent>
      </Card>
      {active && (
        <>
          <AddDocumentsDialog
            hold={h}
            open={adding}
            onClose={() => {
              setAdding(false)
            }}
          />
          <ReleaseDialog
            hold={h}
            open={releasing}
            onClose={() => {
              setReleasing(false)
            }}
          />
          <RemoveDocumentDialog
            hold={h}
            document={removing}
            onClose={() => {
              setRemoving(undefined)
            }}
          />
        </>
      )}
    </div>
  )
}

function DocumentRow({
  document: d,
  removable,
  onRemove,
}: {
  document: HeldDocument
  removable: boolean
  onRemove: () => void
}) {
  const via = d.addedVia === 'Document' || d.addedVia === 'Search' ? d.addedVia : undefined
  return (
    <tr className="border-b last:border-0">
      <td className={td}>
        {d.title !== undefined ? (
          <Link
            to="/documents/$documentId"
            params={{ documentId: d.documentId }}
            className={linkClass}
          >
            {d.title}
          </Link>
        ) : (
          <span className="text-muted-foreground">{t('legalHolds.hiddenTitle')}</span>
        )}
      </td>
      <td className={`${td} whitespace-nowrap`}>
        {t('legalHolds.addedBy', {
          via: via ? t(`legalHolds.via.${via}`) : d.addedVia,
          date: formatDateTime(d.addedAt),
        })}
        <div className="text-xs text-muted-foreground">
          <UserName id={d.addedBy} />
        </div>
      </td>
      <td className={td}>{d.reason}</td>
      <td className={td}>
        {d.removedAt ? (
          <div>
            <Badge variant="outline">
              {t('legalHolds.removed', { date: formatDate(d.removedAt) })}
            </Badge>
            <div className="text-xs text-muted-foreground">
              {d.removedBy && <UserName id={d.removedBy} />}
              {d.removalReason && (
                <div>{t('legalHolds.removedReason', { reason: d.removalReason })}</div>
              )}
            </div>
          </div>
        ) : (
          <Badge variant="secondary">{t('legalHolds.covered')}</Badge>
        )}
      </td>
      <td className="py-2 align-top">
        {removable && (
          <Button
            variant="ghost"
            size="sm"
            aria-label={t('legalHolds.removeLabel', { title: d.title ?? '' })}
            onClick={onRemove}
          >
            {t('legalHolds.remove')}
          </Button>
        )}
      </td>
    </tr>
  )
}

/**
 * Legal holds for records managers (US-08.1, DMS-106): the holds of the tenant, a hold with its
 * documents and history, and setting, extending, narrowing and releasing holds.
 */
export function LegalHoldsPage() {
  const search = useSearch({ from: '/_app/legal-holds' })
  const me = useMe()
  const [creating, setCreating] = useState(false)
  const allowed = canManageLegalHolds(me.data)

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('legalHolds.title')}
        description={t('legalHolds.description')}
        actions={
          allowed && (
            <Button
              onClick={() => {
                setCreating(true)
              }}
            >
              <Plus aria-hidden="true" />
              {t('legalHolds.newHold')}
            </Button>
          )
        }
      />
      {me.isPending ? (
        <LoadingState label={t('legalHolds.loading')} />
      ) : me.isError ? (
        <ErrorState error={me.error} onRetry={() => void me.refetch()} />
      ) : !allowed ? (
        <EmptyState
          icon={Scale}
          title={t('legalHolds.notAllowedTitle')}
          description={t('legalHolds.notAllowedText')}
        />
      ) : search.hold ? (
        <HoldDetail key={search.hold} holdId={search.hold} search={search} />
      ) : (
        <HoldList search={search} />
      )}
      <CreateHoldDialog
        open={creating}
        onClose={() => {
          setCreating(false)
        }}
      />
    </div>
  )
}
