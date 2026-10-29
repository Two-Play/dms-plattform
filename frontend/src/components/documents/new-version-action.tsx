import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { FilePlus2 } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentView } from '@/api/partners'
import { MAX_CHANGE_SUMMARY, startNewVersion, type ChangeKind } from '@/api/versions'
import { Button } from '@/components/ui/button'
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
import { t, tOptional } from '@/i18n'
import { toast } from '@/lib/toast'
import { nextLabels } from './version-labels'

const OPEN_STATUSES = new Set(['Draft', 'InReview', 'InApproval'])

/** A failed start in the caller's language, with the backend's message where it explains the cause. */
function failure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('newVersion.forbidden')
    if (error.status === 409) return t('newVersion.conflict', { message: error.message })
  }
  return t('newVersion.failed', { message: error.message })
}

function NewVersionForm({
  document,
  onDone,
  onCancel,
}: {
  document: DocumentView
  onDone: () => void
  onCancel: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const id = useId()
  const [kind, setKind] = useState<ChangeKind>('Major')
  const [summary, setSummary] = useState('')
  const [attempted, setAttempted] = useState(false)
  const fileDocument = document.kind === 'File'
  const labels = nextLabels(document.current.version)

  const trimmed = summary.trim()
  // Every new version needs a change summary, major and minor (DMS-126, business concept 5.1).
  const problem =
    trimmed === ''
      ? t('newVersion.summaryMissing')
      : trimmed.length > MAX_CHANGE_SUMMARY
        ? t('newVersion.summaryTooLong', { max: MAX_CHANGE_SUMMARY, count: trimmed.length })
        : undefined

  const start = useMutation({
    mutationFn: () => startNewVersion(client, document.id, { changeKind: kind, reason: trimmed }),
    onSuccess: async (created) => {
      toast(
        fileDocument
          ? t('newVersion.startedFile', { label: created.version })
          : t('newVersion.started', { label: created.version }),
      )
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      onDone()
      // The new draft is the document's open version: the page without a picked version shows it.
      void navigate({
        to: '/documents/$documentId',
        params: { documentId: document.id },
        search: {},
      })
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problem) return
    start.mutate()
  }

  const error = attempted ? problem : undefined
  const summaryId = `${id}-summary`
  const hintId = `${id}-summary-hint`
  const options: { value: ChangeKind; label: string; hint: string }[] = [
    { value: 'Major', label: t('newVersion.major'), hint: t('newVersion.majorHint') },
    { value: 'Minor', label: t('newVersion.minor'), hint: t('newVersion.minorHint') },
  ]

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <fieldset className="grid gap-3">
        <legend className="mb-1 text-sm font-medium">{t('newVersion.kind')}</legend>
        {options.map((option) => (
          <label key={option.value} className="flex items-start gap-2 text-sm">
            <input
              type="radio"
              name={`${id}-kind`}
              value={option.value}
              checked={kind === option.value}
              onChange={() => {
                setKind(option.value)
              }}
              aria-describedby={`${id}-${option.value}-hint`}
              className="mt-0.5 size-4 accent-primary"
            />
            <span className="grid gap-0.5">
              <span className="font-medium">
                {option.label}
                {labels && (
                  <span className="font-normal text-muted-foreground">
                    {' '}
                    ({t('newVersion.becomes', { label: labels[option.value] })})
                  </span>
                )}
              </span>
              <span id={`${id}-${option.value}-hint`} className="text-xs text-muted-foreground">
                {option.hint}
              </span>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="grid gap-1.5">
        <Label htmlFor={summaryId}>{t('newVersion.summary')}</Label>
        <Textarea
          id={summaryId}
          value={summary}
          rows={4}
          onChange={(e) => {
            setSummary(e.target.value)
          }}
          aria-required
          aria-invalid={error ? true : undefined}
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={error ? 'alert' : undefined}
          className={error ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {error ?? t('newVersion.summaryHint')}
        </p>
      </div>
      {start.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(start.error)}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={start.isPending}>
          {start.isPending ? t('newVersion.submitting') : t('newVersion.submit')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/**
 * "New version" on the document page (US-05.1, DMS-103): starts the next major or minor version of a valid
 * document as a draft with the author's change summary and opens it. Hidden for callers who may not start
 * versions (`canStartVersion` and `startVersionBlocked` both unset); disabled with the reason while the
 * document's state blocks it, unless the caller already sees the version in progress. The backend checks
 * again.
 */
export function NewVersionAction({ document }: { document: DocumentView }) {
  const [open, setOpen] = useState(false)
  const reasonId = useId()
  const blocked = document.startVersionBlocked
  if (!document.canStartVersion && !blocked) return null
  // The caller works on the version in progress already; a disabled button would only distract.
  if (blocked === 'OpenVersion' && OPEN_STATUSES.has(document.current.status)) return null

  const fileDocument = document.kind === 'File'
  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        size="sm"
        variant="outline"
        disabled={!document.canStartVersion}
        aria-describedby={blocked ? reasonId : undefined}
        onClick={() => {
          setOpen(true)
        }}
      >
        <FilePlus2 className="size-4" aria-hidden />
        {t('newVersion.start')}
      </Button>
      {blocked && (
        <p id={reasonId} className="max-w-xs text-xs text-muted-foreground">
          {tOptional(`newVersion.blocked.${blocked}`) ?? t('newVersion.blockedOther')}
        </p>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('newVersion.title')}</DialogTitle>
            <DialogDescription>
              {fileDocument
                ? t('newVersion.fileDescription', { label: document.current.version })
                : t('newVersion.description', { label: document.current.version })}
            </DialogDescription>
          </DialogHeader>
          {open && (
            <NewVersionForm
              document={document}
              onDone={() => {
                setOpen(false)
              }}
              onCancel={() => {
                setOpen(false)
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
