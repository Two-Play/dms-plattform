import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Scale } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import {
  addLegalHoldDocuments,
  confirmable,
  isResultChanged,
  confirmedFilter,
  createLegalHold,
  invalidateAfterHoldChange,
  legalHoldsQuery,
  previewHoldSearch,
  type HoldSearch,
} from '@/api/legal-holds'
import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { Button } from '@/components/ui/button'
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
import { t } from '@/i18n'
import { toast } from '@/lib/toast'
import { HoldError, MAX_NAME, ReasonField, SearchPreview } from './hold-forms'

const NEW = 'new'

/**
 * Puts the result of the current search under a legal hold (DMS-106), for records managers on the
 * search page: the backend lists the documents of the whole result (all pages), the records
 * manager reviews them and confirms exactly these documents, into an active hold or a new one.
 */
function SearchHoldForm({ search, onClose }: { search: HoldSearch; onClose: () => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const id = useId()
  const holds = useQuery(legalHoldsQuery(client, 'active'))
  const preview = useQuery({
    queryKey: ['legal-hold-preview', search],
    queryFn: () => previewHoldSearch(client, search),
    retry: false,
    gcTime: 0,
  })
  const [target, setTarget] = useState<string | undefined>(undefined)
  const [name, setName] = useState('')
  const [matter, setMatter] = useState('')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)

  const chosen = target ?? holds.data?.[0]?.id ?? NEW
  const isNew = chosen === NEW
  const nameMissing = isNew && name.trim() === ''
  const reasonMissing = reason.trim() === ''

  const mutation = useMutation({
    mutationFn: async () => {
      if (!preview.data) throw new Error('No reviewed search result')
      const searchFilter = confirmedFilter(search, preview.data)
      return isNew
        ? createLegalHold(client, {
            name,
            matterReference: matter || undefined,
            reason,
            searchFilter,
          })
        : addLegalHoldDocuments(client, chosen, { reason, searchFilter })
    },
    onSuccess: async (hold) => {
      if (isNew) toast(t('legalHolds.create.done', { name: hold.name }))
      toast(t('legalHolds.add.done', { count: hold.addedDocumentIds?.length ?? 0 }))
      await invalidateAfterHoldChange(queryClient)
      onClose()
      void navigate({ to: '/legal-holds', search: { hold: hold.id } })
    },
    onError: (error) => {
      if (isResultChanged(error)) void preview.refetch()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (nameMissing || reasonMissing || !confirmable(preview.data)) return
    mutation.mutate()
  }

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      {preview.isPending ? (
        <LoadingState label={t('legalHolds.search.checking')} rows={2} />
      ) : preview.isError ? (
        <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />
      ) : (
        <SearchPreview preview={preview.data} />
      )}
      {holds.isPending ? (
        <LoadingState label={t('legalHolds.fromSearch.loadingHolds')} rows={1} />
      ) : holds.isError ? (
        <ErrorState error={holds.error} onRetry={() => void holds.refetch()} />
      ) : (
        <div className="grid gap-1.5">
          <Label htmlFor={`${id}-target`}>{t('legalHolds.fromSearch.target')}</Label>
          <select
            id={`${id}-target`}
            value={chosen}
            onChange={(e) => {
              setTarget(e.target.value)
            }}
            className="h-9 rounded-md border border-input bg-transparent px-3 text-sm shadow-xs focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            {holds.data.map((h) => (
              <option key={h.id} value={h.id}>
                {h.matterReference ? `${h.name} (${h.matterReference})` : h.name}
              </option>
            ))}
            <option value={NEW}>{t('legalHolds.fromSearch.newTarget')}</option>
          </select>
        </div>
      )}
      {isNew && (
        <div className="grid gap-3 sm:grid-cols-2">
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
              onChange={(e) => {
                setMatter(e.target.value)
              }}
            />
          </div>
        </div>
      )}
      <ReasonField
        id={`${id}-reason`}
        value={reason}
        onChange={setReason}
        invalid={attempted && reasonMissing}
      />
      {mutation.isError && <HoldError error={mutation.error} />}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button
          type="submit"
          disabled={mutation.isPending || preview.isFetching || !confirmable(preview.data)}
        >
          {mutation.isPending
            ? t('common.loading')
            : t('legalHolds.search.submit', { count: preview.data?.documents?.length ?? 0 })}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** The "Put under legal hold" action of the search page with its dialog. */
export function SearchHoldAction({ search }: { search: HoldSearch }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setOpen(true)
        }}
      >
        <Scale aria-hidden="true" />
        {t('legalHolds.fromSearch.button')}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{t('legalHolds.fromSearch.title')}</DialogTitle>
            <DialogDescription>{t('legalHolds.fromSearch.description')}</DialogDescription>
          </DialogHeader>
          {open && (
            <SearchHoldForm
              search={search}
              onClose={() => {
                setOpen(false)
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}
