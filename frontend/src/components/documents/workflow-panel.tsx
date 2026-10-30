import { ErrorState } from '@/components/shared/error-state'
import { LoadingState } from '@/components/shared/states'
import { toast } from '@/lib/toast'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { documentWorkflowQuery, type WorkflowCase } from '@/api/documents'
import { decisionLabel, myTasksQuery, type WorkflowTask } from '@/api/workflow'
import { roleLabel } from '@/components/shared/role-label'
import { UserName, UserNames } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDate, formatDateTime } from '@/i18n/format'
import { PendingVoteNotice } from '@/components/tasks/pending-vote-notice'
import { VoteDialog, type VoteDraft } from '@/components/tasks/vote-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { StatusBadge } from './status-badge'
import { statusLabel } from './status-label'

/** The deadline of an open task (DMS-87), marked when it has passed. */
function DueDate({ dueAt }: { dueAt: string }) {
  const [now] = useState(() => Date.now())
  const overdue = Date.parse(dueAt) < now
  return (
    <span className="text-muted-foreground">
      {' · '}
      {t('docPanels.workflow.dueOn', { date: formatDate(dueAt) })}
      {overdue && (
        <Badge variant="destructive" className="ml-1">
          {t('common.overdue')}
        </Badge>
      )}
    </span>
  )
}

function CaseDetails({ workflowCase, version }: { workflowCase: WorkflowCase; version: string }) {
  const tasks = (workflowCase.tasks ?? []).filter((task) => task.round === workflowCase.round)
  const votes = workflowCase.votes ?? []
  const current = tasks.find((task) => task.step === workflowCase.currentStep)
  return (
    <section
      className="space-y-3 rounded-lg border p-3"
      aria-label={t('docPanels.workflow.caseLabel', { version })}
      data-testid="workflow-case"
    >
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StatusBadge status={workflowCase.status} />
        <span>{t('docPanels.workflow.round', { round: workflowCase.round })}</span>
        {workflowCase.status === 'Running' && current && (
          <span className="text-muted-foreground">
            {t('docPanels.workflow.currentStep', { step: current.stepName })}
          </span>
        )}
        {workflowCase.status === 'Returned' && (
          <span className="text-muted-foreground">{t('docPanels.workflow.returned')}</span>
        )}
        {workflowCase.status === 'Rendering' && (
          <span className="text-muted-foreground">{t('docPanels.workflow.rendering')}</span>
        )}
        {workflowCase.approval && (
          <span className="text-muted-foreground">
            {t('docPanels.workflow.approvedBy')} <UserName id={workflowCase.approval.approvedBy} />{' '}
            {t('docPanels.workflow.approvedOn', {
              date: formatDateTime(workflowCase.approval.approvedAt),
            })}
          </span>
        )}
      </div>
      {tasks.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">
              {t('docPanels.workflow.tasksOfRound', { round: workflowCase.round })}
            </caption>
            <thead className="text-left text-xs text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('docPanels.workflow.step')}
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('docPanels.workflow.role')}
                </th>
                <th scope="col" className="py-1.5 pr-3 font-medium">
                  {t('docPanels.workflow.task')}
                </th>
                <th scope="col" className="py-1.5 font-medium">
                  {t('docPanels.workflow.assignedTo')}
                </th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((task) => (
                <tr key={task.id} className="border-b last:border-0">
                  <td className="py-1.5 pr-3">{task.stepName}</td>
                  <td className="py-1.5 pr-3">{roleLabel(task.role)}</td>
                  <td className="py-1.5 pr-3">
                    {statusLabel(task.status)}
                    {task.completedBy && (
                      <span className="text-muted-foreground">
                        {' '}
                        {t('docPanels.workflow.by')} <UserName id={task.completedBy} />
                      </span>
                    )}
                    {task.status === 'Open' && task.dueAt && <DueDate dueAt={task.dueAt} />}
                  </td>
                  <td className="py-1.5 text-muted-foreground">
                    {task.status === 'Waiting' ? (
                      t('docPanels.workflow.nobody')
                    ) : (
                      <UserNames ids={task.candidates ?? []} />
                    )}
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
              <UserName id={v.voter} />:{' '}
              {t('docPanels.workflow.voteDetail', {
                decision: statusLabel(v.decision),
                round: v.round,
                step: v.step,
              })}
              {v.reason && <> – {v.reason}</>}
              {v.invalidatedAt && (
                <span className="sr-only"> {t('docPanels.workflow.invalidated')}</span>
              )}
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
  const open = (myTasks.data ?? []).filter((task) => task.documentId === documentId)
  return (
    <>
      <PendingVoteNotice
        tasks={open}
        titleOf={() => title}
        onRepeat={(task, pending) => {
          setVoting({ task, initial: { decision: pending.decision, reason: pending.reason } })
        }}
      />
      {open.map((task) => (
        <div
          key={task.id}
          className="flex flex-col gap-2 rounded-lg border border-primary/40 p-3 text-sm sm:flex-row sm:items-center sm:justify-between"
        >
          <span>
            {t('docPanels.workflow.yourTask')} <strong>{task.stepName}</strong> (
            {roleLabel(task.role)})
          </span>
          <Button
            size="sm"
            onClick={() => {
              setVoting({ task })
            }}
          >
            {t('docPanels.workflow.vote')}
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
          toast(t('docPanels.workflow.voteRecorded', { decision: decisionLabel(decision) }))
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
        <CardTitle>{t('docPanels.workflow.title')}</CardTitle>
        <CardDescription>{t('docPanels.workflow.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <MyOpenTasks documentId={documentId} title={title} />
        {workflow.isPending && <LoadingState label={t('docPanels.workflow.loading')} rows={2} />}
        {workflow.isError && (
          <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} />
        )}
        {workflow.data?.length === 0 && (
          <p className="text-sm text-muted-foreground">{t('docPanels.workflow.notSubmitted')}</p>
        )}
        {workflow.data
          ?.slice()
          .reverse()
          .map((c) => (
            <CaseDetails
              key={c.id}
              workflowCase={c}
              version={versions[c.versionId] ?? t('docPanels.workflow.earlierVersion')}
            />
          ))}
      </CardContent>
    </Card>
  )
}
