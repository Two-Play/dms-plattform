import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Hash } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { assignDocumentNumber } from '@/api/number-ranges'
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
import { Textarea } from '@/components/ui/textarea'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/** Document numbers as the backend accepts them (`DocumentNumber.validate`). */
export const documentNumberPattern = /^[\p{L}\p{N}][\p{L}\p{N}._-]{0,63}$/u

function AssignNumberForm({
  documentId,
  current,
  onDone,
  onCancel,
}: {
  documentId: string
  current?: string
  onDone: () => void
  onCancel: () => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [number, setNumber] = useState(current ?? '')
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const numberProblem = documentNumberPattern.test(number.trim())
    ? undefined
    : t('docNumber.numberProblem')
  const reasonProblem = reason.trim() === '' ? t('docNumber.reasonMissing') : undefined

  const assign = useMutation({
    mutationFn: () => assignDocumentNumber(client, documentId, number.trim(), reason.trim()),
    onSuccess: async (result) => {
      toast(t('docNumber.assigned', { number: result.documentNumber }))
      await queryClient.invalidateQueries({ queryKey: ['documents'] })
      onDone()
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (numberProblem ?? reasonProblem) return
    assign.mutate()
  }

  const numberError = attempted ? numberProblem : undefined
  const reasonError = attempted ? reasonProblem : undefined
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-number`}>{t('docNumber.number')}</Label>
        <Input
          id={`${id}-number`}
          value={number}
          className="font-mono"
          onChange={(e) => {
            setNumber(e.target.value)
          }}
          aria-required
          aria-invalid={numberError ? true : undefined}
          aria-describedby={`${id}-number-hint`}
        />
        <p
          id={`${id}-number-hint`}
          role={numberError ? 'alert' : undefined}
          className={numberError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {numberError ?? t('docNumber.numberHint')}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-reason`}>{t('docNumber.reason')}</Label>
        <Textarea
          id={`${id}-reason`}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-required
          aria-invalid={reasonError ? true : undefined}
          aria-describedby={reasonError ? `${id}-reason-hint` : undefined}
        />
        {reasonError && (
          <p id={`${id}-reason-hint`} role="alert" className="text-sm text-destructive">
            {reasonError}
          </p>
        )}
      </div>
      {assign.isError && (
        <p role="alert" className="text-sm text-destructive">
          {assign.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={assign.isPending}>
          {assign.isPending ? t('docNumber.submitting') : t('docNumber.submit')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/**
 * The document number (DMS-113) and, for records managers and the document's owner while the
 * number is not frozen yet, the action to assign it manually with a reason. The backend decides
 * who may (`canAssignNumber`) and checks again.
 */
export function DocumentNumberAction({
  documentId,
  documentNumber,
  canAssign,
}: {
  documentId: string
  documentNumber?: string
  canAssign: boolean
}) {
  const [open, setOpen] = useState(false)
  if (!documentNumber && !canAssign) return null
  return (
    <div className="flex items-center gap-2 text-sm" data-testid="document-number">
      {documentNumber && (
        <span className="font-mono">{t('docNumber.label', { number: documentNumber })}</span>
      )}
      {canAssign && (
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            setOpen(true)
          }}
        >
          <Hash className="size-4" aria-hidden />
          {documentNumber ? t('docNumber.change') : t('docNumber.assign')}
        </Button>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('docNumber.title')}</DialogTitle>
            <DialogDescription>{t('docNumber.description')}</DialogDescription>
          </DialogHeader>
          {open && (
            <AssignNumberForm
              documentId={documentId}
              current={documentNumber}
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
