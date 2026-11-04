import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Undo2 } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { ApiRequestError } from '@/api/errors'
import type { DocumentView } from '@/api/partners'
import { MAX_WITHDRAWAL_REASON, withdrawVersion, type WithdrawableVersion } from '@/api/versions'
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
import { formatDate } from '@/i18n/format'
import { toast } from '@/lib/toast'

/** A failed withdrawal in the caller's language: the codes tell who won a race with the publication. */
function failure(error: Error): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return t('withdrawVersion.forbidden')
    const known = error.code ? tOptional(`withdrawVersion.refused.${error.code}`) : undefined
    if (known !== undefined) return known
    if (error.status === 409) return t('withdrawVersion.conflict', { message: error.message })
  }
  return t('withdrawVersion.failed', { message: error.message })
}

function WithdrawForm({
  documentId,
  version,
  onDone,
  onCancel,
}: {
  documentId: string
  version: WithdrawableVersion
  onDone: () => void
  onCancel: () => void
}) {
  const client = useApiClient()
  const navigate = useNavigate()
  const id = useId()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const trimmed = reason.trim()
  const problem =
    trimmed === ''
      ? t('withdrawVersion.reasonMissing')
      : trimmed.length > MAX_WITHDRAWAL_REASON
        ? t('withdrawVersion.reasonTooLong', {
            max: MAX_WITHDRAWAL_REASON,
            count: trimmed.length,
          })
        : undefined

  const withdraw = useMutation({
    mutationFn: () => withdrawVersion(client, documentId, version.versionId, trimmed),
    onSuccess: async () => {
      toast(
        version.validVersion
          ? t('withdrawVersion.withdrawn', { label: version.version, valid: version.validVersion })
          : t('withdrawVersion.withdrawnNoValid', { label: version.version }),
      )
      onDone()
      // Without a picked version the page shows the valid version again.
      await navigate({ to: '/documents/$documentId', params: { documentId }, search: {} })
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problem) return
    withdraw.mutate()
  }

  const error = attempted ? problem : undefined
  const reasonId = `${id}-reason`
  const hintId = `${id}-reason-hint`
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={reasonId}>{t('withdrawVersion.reason')}</Label>
        <Textarea
          id={reasonId}
          value={reason}
          rows={3}
          onChange={(e) => {
            setReason(e.target.value)
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
          {error ?? t('withdrawVersion.reasonHint')}
        </p>
      </div>
      {withdraw.isError && (
        <p role="alert" className="text-sm text-destructive">
          {failure(withdraw.error)}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="destructive" disabled={withdraw.isPending}>
          {withdraw.isPending ? t('withdrawVersion.confirming') : t('withdrawVersion.confirm')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** What withdrawing means for this version: its date may have passed if its publication failed (DMS-145). */
function description(version: WithdrawableVersion): string {
  const label = version.version
  const valid = version.validVersion
  if (version.publicationFailed) {
    return valid
      ? t('withdrawVersion.descriptionFailed', { label, valid })
      : t('withdrawVersion.descriptionFailedNoValid', { label })
  }
  const validFrom = version.validFrom ? formatDate(version.validFrom) : ''
  return valid
    ? t('withdrawVersion.description', { label, validFrom, valid })
    : t('withdrawVersion.descriptionNoValid', { label, validFrom })
}

/**
 * "Withdraw version" on the document page (DMS-143, ADR 0059): stops an approved version before its valid-from
 * date, or later if its publication failed for good (DMS-145, ADR 0061), for callers the backend names the
 * version to (`withdrawable`: owner role or having approved it). A confirmation dialog with a required reason.
 * The backend decides again; if the publication or a retry got there first, the dialog says so, and closing it
 * reloads the document.
 */
export function WithdrawVersionAction({ document }: { document: DocumentView }) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const version = document.withdrawable
  if (!version) return null

  function close() {
    setOpen(false)
    void queryClient.invalidateQueries({ queryKey: ['documents'] })
  }

  return (
    <>
      <Button
        size="sm"
        variant="destructive"
        onClick={() => {
          setOpen(true)
        }}
      >
        <Undo2 className="size-4" aria-hidden />
        {t('withdrawVersion.action', { label: version.version })}
      </Button>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (next) setOpen(true)
          else close()
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('withdrawVersion.title', { label: version.version })}</DialogTitle>
            <DialogDescription>{description(version)}</DialogDescription>
          </DialogHeader>
          {open && (
            <WithdrawForm
              documentId={document.id}
              version={version}
              onDone={close}
              onCancel={close}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}
