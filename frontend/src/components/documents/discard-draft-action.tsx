import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Trash2 } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentView } from '@/api/partners'
import { discardDraft, MAX_DISCARD_REASON } from '@/api/versions'
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
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/** A failed discard in the caller's language, with the backend's message where it explains the cause. */
function failure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('discardDraft.forbidden')
    if (error.status === 409) return t('discardDraft.conflict', { message: error.message })
  }
  return t('discardDraft.failed', { message: error.message })
}

/** A draft of the first version (1.0) has no valid version to fall back to: the document goes with it. */
function isFirstVersion(label: string): boolean {
  return label.startsWith('1.0-')
}

function DiscardForm({ document, onCancel }: { document: DocumentView; onCancel: () => void }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const id = useId()
  const [reason, setReason] = useState('')
  const trimmed = reason.trim()
  const tooLong = trimmed.length > MAX_DISCARD_REASON

  const discard = useMutation({
    mutationFn: () => discardDraft(client, document.id, trimmed),
    onSuccess: async (result) => {
      if (result.documentDiscarded) {
        toast(t('discardDraft.discardedDocument', { label: result.version }))
        // The document is gone: leave its page first, so that it is not loaded again (404).
        await navigate({ to: '/documents' })
        queryClient.removeQueries({ queryKey: ['documents', document.id] })
        await queryClient.invalidateQueries({ queryKey: ['documents'] })
      } else {
        toast(
          t('discardDraft.discardedVersion', {
            label: result.version,
            valid: result.validVersion ?? '',
          }),
        )
        await queryClient.invalidateQueries({ queryKey: ['documents'] })
        // Without a picked version the page shows the valid version again.
        await navigate({
          to: '/documents/$documentId',
          params: { documentId: document.id },
          search: {},
        })
      }
      await queryClient.invalidateQueries({ queryKey: ['workflow', 'tasks'] })
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    if (tooLong) return
    discard.mutate()
  }

  const reasonId = `${id}-reason`
  const hintId = `${id}-reason-hint`
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <p className="text-sm text-muted-foreground">{t('discardDraft.attachmentsNote')}</p>
      <div className="grid gap-1.5">
        <Label htmlFor={reasonId}>{t('discardDraft.reason')}</Label>
        <Textarea
          id={reasonId}
          value={reason}
          rows={3}
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-invalid={tooLong ? true : undefined}
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={tooLong ? 'alert' : undefined}
          className={tooLong ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {tooLong
            ? t('discardDraft.reasonTooLong', { max: MAX_DISCARD_REASON, count: trimmed.length })
            : t('discardDraft.reasonHint')}
        </p>
      </div>
      {discard.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(discard.error)}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="destructive" disabled={discard.isPending}>
          {discard.isPending ? t('discardDraft.confirming') : t('discardDraft.confirm')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/**
 * "Discard draft" on the document page (DMS-129): for the draft's author, the document's owner and tenant
 * administrators while the open version is a draft that is not submitted (`canDiscardDraft`). A confirmation
 * dialog with an optional reason; afterwards the document list (first version) or the valid version. The
 * backend checks again.
 */
export function DiscardDraftAction({ document }: { document: DocumentView }) {
  const [open, setOpen] = useState(false)
  if (!document.canDiscardDraft || document.current.status !== 'Draft') return null
  const label = document.current.version
  return (
    <>
      <Button
        size="sm"
        variant="destructive"
        onClick={() => {
          setOpen(true)
        }}
      >
        <Trash2 className="size-4" aria-hidden />
        {t('discardDraft.action')}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('discardDraft.title')}</DialogTitle>
            <DialogDescription>
              {isFirstVersion(label)
                ? t('discardDraft.descriptionFirst', { label })
                : t('discardDraft.descriptionNew', { label })}
            </DialogDescription>
          </DialogHeader>
          {open && (
            <DiscardForm
              document={document}
              onCancel={() => {
                setOpen(false)
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}
