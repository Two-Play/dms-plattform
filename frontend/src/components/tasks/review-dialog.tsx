import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useState, type SyntheticEvent } from 'react'
import { useApiClient } from '@/api/context'
import { invalidateAfterReview, recertify, revise, withdraw, type ReviewTask } from '@/api/reviews'
import { MAX_CHANGE_SUMMARY } from '@/api/versions'
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
import { formatPlainDate } from '@/i18n/format'
import type { ReviewDecision } from './inbox-items'

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
        <Label htmlFor={`${id}-date`}>
          {dateRequired
            ? t('tasks.reviewDialog.nextDateRequired')
            : t('tasks.reviewDialog.nextDateOptional')}
        </Label>
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
            ? t('tasks.reviewDialog.dateMissing')
            : task.reviewIntervalMonths !== undefined
              ? t('tasks.reviewDialog.defaultInterval', { count: task.reviewIntervalMonths })
              : t('tasks.reviewDialog.noInterval')}
        </p>
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-comment`}>{t('tasks.reviewDialog.note')}</Label>
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
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending
            ? t('tasks.reviewDialog.recertifying')
            : t('tasks.reviewDialog.recertify')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/**
 * Revision required: starts the next version with the change summary every new version needs (DMS-126), or
 * links the draft already in progress, and opens the document.
 */
function ReviseForm({ task, onDone, onCancel }: FormProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const id = useId()
  const [summary, setSummary] = useState('')
  const [attempted, setAttempted] = useState(false)
  const trimmed = summary.trim()
  const problem =
    trimmed === ''
      ? t('tasks.reviewDialog.summaryMissing')
      : trimmed.length > MAX_CHANGE_SUMMARY
        ? t('newVersion.summaryTooLong', { max: MAX_CHANGE_SUMMARY, count: trimmed.length })
        : undefined

  const mutation = useMutation({
    mutationFn: () => revise(client, task.id, { reason: trimmed }),
    onSuccess: async (decided) => {
      await invalidateAfterReview(queryClient, task.documentId)
      onDone(decided)
      await navigate({ to: '/documents/$documentId', params: { documentId: task.documentId } })
    },
  })

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (problem) return
    mutation.mutate()
  }

  const error = attempted ? problem : undefined
  const hintId = `${id}-summary-hint`
  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-summary`}>{t('tasks.reviewDialog.summary')}</Label>
        <Textarea
          id={`${id}-summary`}
          value={summary}
          rows={4}
          onChange={(e) => {
            setSummary(e.target.value)
          }}
          aria-invalid={error ? true : undefined}
          aria-required
          aria-describedby={hintId}
        />
        <p
          id={hintId}
          role={error ? 'alert' : undefined}
          className={error ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
        >
          {error ?? t('tasks.reviewDialog.summaryHint')}
        </p>
      </div>
      {mutation.isError && (
        <p role="alert" className="text-sm text-destructive">
          {mutation.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? t('tasks.reviewDialog.revising') : t('tasks.reviewDialog.revise')}
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
        <Label htmlFor={`${id}-reason`}>{t('tasks.reviewDialog.reason')}</Label>
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
          {reasonError ? t('tasks.reviewDialog.reasonMissing') : t('tasks.reviewDialog.reasonHint')}
        </p>
      </div>
      {mutation.isError && (
        <p role="alert" className="text-sm text-destructive">
          {mutation.error.message}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" variant="destructive" disabled={mutation.isPending}>
          {mutation.isPending
            ? t('tasks.reviewDialog.withdrawing')
            : t('tasks.reviewDialog.withdrawVersion')}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** Dialog for the decisions of a review task that need input: recertify, revise or withdraw (DMS-55). */
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
              {t(
                decision === 'recertify'
                  ? 'tasks.reviewDialog.recertifyTitle'
                  : decision === 'revise'
                    ? 'tasks.reviewDialog.reviseTitle'
                    : 'tasks.reviewDialog.withdrawTitle',
                { title: task.title, version: task.versionLabel },
              )}
            </DialogTitle>
            <DialogDescription>
              {decision === 'recertify'
                ? t('tasks.reviewDialog.recertifyText', { date: formatPlainDate(task.dueOn) })
                : decision === 'revise'
                  ? t('tasks.reviewDialog.reviseText')
                  : t('tasks.reviewDialog.withdrawText')}
            </DialogDescription>
          </DialogHeader>
          {decision === 'recertify' ? (
            <RecertifyForm task={task} onDone={onDone} onCancel={onClose} />
          ) : decision === 'revise' ? (
            <ReviseForm task={task} onDone={onDone} onCancel={onClose} />
          ) : (
            <WithdrawForm task={task} onDone={onDone} onCancel={onClose} />
          )}
        </DialogContent>
      )}
    </Dialog>
  )
}
