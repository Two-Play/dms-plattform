import { useState } from 'react'
import { decisionLabel, type WorkflowTask } from '@/api/workflow'
import { clearPendingVote, loadPendingVote, type PendingVote } from '@/auth/pending-vote'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'

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
  const task = pending && tasks.find((task) => task.id === pending.taskId)
  if (!pending || !task) return null
  return (
    <div
      role="status"
      className="flex flex-col gap-3 rounded-lg border border-primary/40 bg-primary/5 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
    >
      <p>
        {t('tasks.pending.text', {
          decision: decisionLabel(pending.decision),
          title: titleOf(task) ?? t('tasks.aDocument'),
          step: task.stepName,
        })}
      </p>
      <div className="flex shrink-0 gap-2">
        <Button
          size="sm"
          onClick={() => {
            onRepeat(task, pending)
            setPending(undefined)
          }}
        >
          {t('tasks.pending.repeat')}
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            clearPendingVote()
            setPending(undefined)
          }}
        >
          {t('tasks.pending.discard')}
        </Button>
      </div>
    </div>
  )
}
