import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { toast } from '@/lib/toast'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentWorkflowQuery, type WorkflowCase } from '@/api/documents'
import { decisionLabels, myTasksQuery, type WorkflowTask } from '@/api/workflow'
import { PendingVoteNotice } from '@/components/tasks/pending-vote-notice'
import { VoteDialog, type VoteDraft } from '@/components/tasks/vote-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { StatusBadge } from './status-badge'
import { statusLabel } from './status-label'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function CaseDetails({ workflowCase, version }: { workflowCase: WorkflowCase; version: string }) {
  const tasks = (workflowCase.tasks ?? []).filter((t) => t.round === workflowCase.round)
  const votes = workflowCase.votes ?? []
  const current = tasks.find((t) => t.step === workflowCase.currentStep)
  return (
    <section
      className="space-y-3 rounded-lg border p-3"
      aria-label={`Workflow of version ${version}`}
      data-testid="workflow-case"
    >
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StatusBadge status={workflowCase.status} />
        <span>Round {workflowCase.round}</span>
        {workflowCase.status === 'Running' && current && (
          <span className="text-muted-foreground">· current step: {current.stepName}</span>
        )}
        {workflowCase.status === 'Returned' && (
          <span className="text-muted-foreground">
            · returned to the author; change the draft and submit it again
          </span>
        )}
        {workflowCase.status === 'Rendering' && (
          <span className="text-muted-foreground">
            · all steps approved; the archive PDF is being produced, then the version is approved
          </span>
        )}
        {workflowCase.approval && (
          <span className="text-muted-foreground">
            · approved by {workflowCase.approval.approvedBy} on{' '}
            {dateTime.format(new Date(workflowCase.approval.approvedAt))}
          </span>
        )}
      </div>
      {tasks.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Tasks of round {workflowCase.round}</caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  Step
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  Role
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  Task
                </th>
                <th scope="col" className="py-1.5 font-medium">
                  Assigned to
                </th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((t) => (
                <tr key={t.id} className="border-b last:border-0">
                  <td className="py-1.5 pr-3">{t.stepName}</td>
                  <td className="py-1.5 pr-3">{t.role}</td>
                  <td className="py-1.5 pr-3">
                    {statusLabel(t.status)}
                    {t.completedBy && (
                      <span className="text-muted-foreground"> by {t.completedBy}</span>
                    )}
                  </td>
                  <td className="py-1.5 text-muted-foreground">
                    {t.status === 'Waiting'
                      ? 'Nobody has this role yet'
                      : (t.candidates ?? []).join(', ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {votes.length > 0 && (
        <ul className="space-y-1 text-sm">
          {votes.map((v) => (
            <li key={v.id} className={v.invalidatedAt ? 'text-muted-foreground line-through' : ''}>
              {v.voter}: {statusLabel(v.decision)} (round {v.round}, {v.step})
              {v.reason && <> – {v.reason}</>}
              {v.invalidatedAt && <span className="sr-only"> (invalidated by a later change)</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** The caller's open tasks on this document with a button to vote on each (DMS-91). */
function MyOpenTasks({ documentId, title }: { documentId: string; title: string | undefined }) {
  const client = useApiClient()
  const myTasks = useQuery(myTasksQuery(client))
  const [voting, setVoting] = useState<{ task: WorkflowTask; initial?: VoteDraft }>()
  const open = (myTasks.data ?? []).filter((t) => t.documentId === documentId)
  return (
    <>
      <PendingVoteNotice
        tasks={open}
        titleOf={() => title}
        onRepeat={(task, pending) => {
          setVoting({ task, initial: { decision: pending.decision, reason: pending.reason } })
        }}
      />
      {open.map((t) => (
        <div
          key={t.id}
          className="flex flex-col gap-2 rounded-lg border border-primary/40 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
        >
          <span>
            Your task: <strong>{t.stepName}</strong> ({t.role})
          </span>
          <Button
            size="sm"
            onClick={() => {
              setVoting({ task: t })
            }}
          >
            Vote…
          </Button>
        </div>
      ))}
      <VoteDialog
        task={voting?.task}
        documentTitle={title}
        initial={voting?.initial}
        onClose={() => {
          setVoting(undefined)
        }}
        onVoted={(_task, decision) => {
          setVoting(undefined)
          toast(`Vote recorded: ${decisionLabels[decision]}.`)
        }}
      />
    </>
  )
}

/** Review and approval state of a document (GET /api/v1/documents/{id}/workflow), newest case first. */
export function WorkflowPanel({
  documentId,
  title,
  versions,
}: {
  documentId: string
  /** Document title, shown in the vote dialog. */
  title?: string
  /** Version labels by version id. */
  versions: Record<string, string>
}) {
  const client = useApiClient()
  const workflow = useQuery(documentWorkflowQuery(client, documentId))
  return (
    <Card>
      <CardHeader>
        <CardTitle>Review and approval</CardTitle>
        <CardDescription>The workflow started when the document was submitted.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <MyOpenTasks documentId={documentId} title={title} />
        {workflow.isPending && <LoadingState label="Loading the workflow" rows={2} />}
        {workflow.isError && (
          <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} />
        )}
        {workflow.data?.length === 0 && (
          <p className="text-sm text-muted-foreground">Not submitted yet.</p>
        )}
        {workflow.data
          ?.slice()
          .reverse()
          .map((c) => (
            <CaseDetails
              key={c.id}
              workflowCase={c}
              version={versions[c.versionId] ?? 'an earlier version'}
            />
          ))}
      </CardContent>
    </Card>
  )
}
