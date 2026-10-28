import { useState } from 'react'
import { decisionLabels, type WorkflowTask } from '@/api/workflow'
import { clearPendingVote, loadPendingVote, type PendingVote } from '@/auth/pending-vote'
import { Button } from '@/components/ui/button'

/**
 * After the step-up sign-in: offers to repeat the vote that needed it, if its task is among `tasks`
 * (still open for the caller). The vote is only cast again when the user confirms it in the dialog.
 */
export function PendingVoteNotice({
  tasks,
  titleOf,
  onRepeat,
}: {
  tasks: readonly WorkflowTask[]
  titleOf: (task: WorkflowTask) => string | undefined
  onRepeat: (task: WorkflowTask, pending: PendingVote) => void
}) {
  const [pending, setPending] = useState<PendingVote | undefined>(() => loadPendingVote())
  const task = pending && tasks.find((t) => t.id === pending.taskId)
  if (!pending || !task) return null
  return (
    <div
      role="status"
      className="flex flex-col gap-3 rounded-lg border border-primary/40 bg-primary/5 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
    >
      <p>
        Your vote “{decisionLabels[pending.decision]}” on {titleOf(task) ?? 'a document'} (
        {task.stepName}) was not recorded because a fresh sign-in was needed. You are signed in
        again and can repeat it now.
      </p>
      <div className="flex shrink-0 gap-2">
        <Button
          size="sm"
          onClick={() => {
            onRepeat(task, pending)
            setPending(undefined)
          }}
        >
          Repeat vote
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            clearPendingVote()
            setPending(undefined)
          }}
        >
          Discard
        </Button>
      </div>
    </div>
  )
}
