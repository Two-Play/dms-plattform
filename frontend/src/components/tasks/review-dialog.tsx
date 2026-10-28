import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { invalidateAfterReview, recertify, withdraw, type ReviewTask } from '@/api/reviews'
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
import { formatPlainDate, type ReviewDecision } from './inbox-items'

interface FormProps {
  task: ReviewTask
  onDone: (task: ReviewTask) => void
  onCancel: () => void
}

function RecertifyForm({ task, onDone, onCancel }: FormProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [nextReviewOn, setNextReviewOn] = useState('')
  const [comment, setComment] = useState('')
  const [attempted, setAttempted] = useState(false)
  const dateRequired = task.reviewIntervalMonths === undefined
  const dateMissing = dateRequired && nextReviewOn === ''

  const mutation = useMutation({
    mutationFn: () =>
      recertify(client, task.id, {
        nextReviewOn: nextReviewOn === '' ? undefined : nextReviewOn,
        comment: comment.trim() === '' ? undefined : comment.trim(),
      }),
    onSuccess: async (decided) => {
      await invalidateAfterReview(queryClient, task.documentId)
      onDone(decided)
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (dateMissing) return
    mutation.mutate()
  }

  const dateError = attempted && dateMissing
  const dateHintId = `${id}-date-hint`
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-date`}>Next review date{dateRequired ? ' *' : ' (optional)'}</Label>
        <Input
          id={`${id}-date`}
          type="date"
          value={nextReviewOn}
          onChange={(e) => {
            setNextReviewOn(e.target.value)
          }}
          aria-invalid={dateError || undefined}
          aria-required={dateRequired}
          aria-describedby={dateHintId}
        />
        <p
          id={dateHintId}
          role={dateError ? 'alert' : undefined}
          className={dateError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {dateError
            ? 'Choose the next review date: the class has no review interval.'
            : task.reviewIntervalMonths !== undefined
              ? `Default: today plus the class's review interval (${String(task.reviewIntervalMonths)} months).`
              : 'The class has no review interval.'}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-comment`}>Note (optional)</Label>
        <Textarea
          id={`${id}-comment`}
          value={comment}
          onChange={(e) => {
            setComment(e.target.value)
          }}
        />
      </div>
      {mutation.isError && (
        <p role="alert" className="text-sm text-destructive">
          {mutation.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Recertifying…' : 'Recertify'}
        </Button>
      </DialogFooter>
    </form>
  )
}

function WithdrawForm({ task, onDone, onCancel }: FormProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const id = useId()
  const [reason, setReason] = useState('')
  const [attempted, setAttempted] = useState(false)
  const reasonMissing = reason.trim() === ''

  const mutation = useMutation({
    mutationFn: () => withdraw(client, task.id, reason.trim()),
    onSuccess: async (decided) => {
      await invalidateAfterReview(queryClient, task.documentId)
      onDone(decided)
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (reasonMissing) return
    mutation.mutate()
  }

  const reasonError = attempted && reasonMissing
  const hintId = `${id}-reason-hint`
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-reason`}>Reason *</Label>
        <Textarea
          id={`${id}-reason`}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-invalid={reasonError || undefined}
          aria-required
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={reasonError ? 'alert' : undefined}
          className={reasonError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {reasonError
            ? 'A reason is required.'
            : 'Why the document is not needed any more; recorded in the audit trail.'}
        </p>
      </div>
      {mutation.isError && (
        <p role="alert" className="text-sm text-destructive">
          {mutation.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" variant="destructive" disabled={mutation.isPending}>
          {mutation.isPending ? 'Withdrawing…' : 'Withdraw version'}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** Dialog for the decisions of a review task that need input: recertify or withdraw (DMS-55). */
export function ReviewDialog({
  task,
  decision,
  onClose,
  onDone,
}: {
  /** The task to decide on; the dialog is open while it is set. */
  task: ReviewTask | undefined
  decision: ReviewDecision
  onClose: () => void
  onDone: (task: ReviewTask) => void
}) {
  return (
    <Dialog
      open={task !== undefined}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      {task && (
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {decision === 'recertify' ? 'Recertify' : 'Withdraw'}: {task.title}{' '}
              {task.versionLabel}
            </DialogTitle>
            <DialogDescription>
              {decision === 'recertify'
                ? `The version stays valid without a change and gets a new review date. Review date: ${formatPlainDate(task.dueOn)}.`
                : 'The version goes out of force and readers no longer see it as valid. Other versions of the document are not affected.'}
            </DialogDescription>
          </DialogHeader>
          {decision === 'recertify' ? (
            <RecertifyForm task={task} onDone={onDone} onCancel={onClose} />
          ) : (
            <WithdrawForm task={task} onDone={onDone} onCancel={onClose} />
          )}
        </DialogContent>
      )}
    </Dialog>
  )
}
