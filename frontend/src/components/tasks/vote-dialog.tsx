import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useAuth } from 'react-oidc-context'
import { useApiClient } from '@/api/context'
import {
  castVote,
  decisionLabels,
  decisionsFor,
  invalidateAfterVote,
  isApprovalStep,
  needsReason,
  type VoteDecision,
  type WorkflowTask,
} from '@/api/workflow'
import { clearPendingVote, savePendingVote } from '@/auth/pending-vote'
import { isReauthenticationRequired, reauthenticate } from '@/auth/step-up'
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

export interface VoteDraft {
  decision: VoteDecision
  reason: string
}

interface VoteFormProps {
  task: WorkflowTask
  initial: VoteDraft | undefined
  onVoted: (decision: VoteDecision) => void
  onCancel: () => void
}

function VoteForm({ task, initial, onVoted, onCancel }: VoteFormProps) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const auth = useAuth()
  const id = useId()
  const [decision, setDecision] = useState<VoteDecision | undefined>(initial?.decision)
  const [reason, setReason] = useState(initial?.reason ?? '')
  const [attempted, setAttempted] = useState(false)
  const [stepUp, setStepUp] = useState(false)

  const vote = useMutation({
    mutationFn: (v: VoteDraft) =>
      castVote(client, task.id, {
        decision: v.decision,
        reason: v.reason.trim() === '' ? undefined : v.reason.trim(),
      }),
    onSuccess: async (_data, v) => {
      clearPendingVote()
      await invalidateAfterVote(queryClient, task.documentId)
      onVoted(v.decision)
    },
    onError: (error, v) => {
      if (isReauthenticationRequired(error)) {
        savePendingVote({ taskId: task.id, documentId: task.documentId, ...v })
        setStepUp(true)
      }
    },
  })

  const reasonMissing = decision !== undefined && needsReason(decision) && reason.trim() === ''
  const decisionMissing = decision === undefined

  function submit(e: SyntheticEvent) {
    e.preventDefault()
    setAttempted(true)
    if (decision === undefined || reasonMissing) return
    setStepUp(false)
    vote.mutate({ decision, reason })
  }

  const reasonError = attempted && reasonMissing
  const reasonId = `${id}-reason`
  const reasonHintId = `${id}-reason-hint`

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <fieldset className="grid gap-2" aria-describedby={`${id}-decision-error`}>
        <legend className="mb-1 text-sm font-medium">Decision</legend>
        {decisionsFor(task).map((d) => (
          <label key={d} className="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name={`${id}-decision`}
              value={d}
              checked={decision === d}
              onChange={() => {
                setDecision(d)
              }}
              className="size-4 accent-primary"
            />
            {decisionLabels[d]}
          </label>
        ))}
        <p id={`${id}-decision-error`} role="alert" className="text-sm text-destructive">
          {attempted && decisionMissing ? 'Choose a decision.' : ''}
        </p>
      </fieldset>
      <div className="grid gap-1.5">
        <Label htmlFor={reasonId}>
          Reason{decision !== undefined && needsReason(decision) ? ' *' : ' (optional)'}
        </Label>
        <Textarea
          id={reasonId}
          value={reason}
          onChange={(e) => {
            setReason(e.target.value)
          }}
          aria-invalid={reasonError || undefined}
          aria-required={decision !== undefined && needsReason(decision)}
          aria-describedby={reasonHintId}
        />
        <p
          id={reasonHintId}
          className={reasonError ? 'text-sm text-destructive' : 'text-xs text-muted-foreground'}
          role={reasonError ? 'alert' : undefined}
        >
          {reasonError
            ? 'A reason is required for this decision.'
            : 'Required for every decision except a plain approval.'}
        </p>
      </div>
      {isApprovalStep(task) && (
        <p className="text-xs text-muted-foreground">
          Approving needs a recent sign-in. If yours is too old, you are asked to sign in again and
          can then repeat the vote.
        </p>
      )}
      {stepUp ? (
        <div role="alert" className="grid gap-2 rounded-lg border border-destructive/40 p-3">
          <p className="text-sm">
            Your vote was not recorded: approving needs a fresh sign-in. Sign in again; afterwards
            you can repeat this vote.
          </p>
          <Button
            type="button"
            className="justify-self-start"
            onClick={() => void reauthenticate((args) => auth.signinRedirect(args))}
          >
            Sign in again
          </Button>
        </div>
      ) : (
        vote.isError && (
          <p role="alert" className="text-sm text-destructive">
            {vote.error.message}
          </p>
        )
      )}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={vote.isPending}>
          {vote.isPending ? 'Casting vote…' : 'Cast vote'}
        </Button>
      </DialogFooter>
    </form>
  )
}

/** Dialog to vote on a review or approval task; handles the step-up sign-in for approvals (DMS-91). */
export function VoteDialog({
  task,
  documentTitle,
  initial,
  onClose,
  onVoted,
}: {
  /** The task to vote on; the dialog is open while it is set. */
  task: WorkflowTask | undefined
  documentTitle: string | undefined
  /** Decision and reason to start with, e.g. a vote repeated after the step-up sign-in. */
  initial?: VoteDraft
  onClose: () => void
  onVoted: (task: WorkflowTask, decision: VoteDecision) => void
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
              {task.stepName}: {documentTitle ?? 'document'}
            </DialogTitle>
            <DialogDescription>
              Your vote applies to exactly this version; a later change invalidates it.
            </DialogDescription>
          </DialogHeader>
          <VoteForm
            task={task}
            initial={initial}
            onCancel={onClose}
            onVoted={(decision) => {
              onVoted(task, decision)
            }}
          />
        </DialogContent>
      )}
    </Dialog>
  )
}
