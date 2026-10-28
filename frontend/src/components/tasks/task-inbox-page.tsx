import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'
import {
  acknowledge,
  markOpened,
  openAcknowledgementsQuery,
  type Acknowledgement,
} from '@/api/acknowledgements'
import { useApiClient } from '@/api/context'
import { documentQuery } from '@/api/documents'
import type { DocumentView } from '@/api/partners'
import { decisionLabels, myTasksQuery, type WorkflowTask } from '@/api/workflow'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import {
  acknowledgementItem,
  isOverdue,
  kindLabels,
  sortInbox,
  workflowItem,
  type InboxItem,
} from './inbox-items'
import { PendingVoteNotice } from './pending-vote-notice'
import { VoteDialog, type VoteDraft } from './vote-dialog'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
})
/** For plain dates (yyyy-mm-dd), which `Date.parse` reads as UTC midnight. */
const plainDateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })

function ItemDate({ item, overdue }: { item: InboxItem; overdue: boolean }) {
  if (item.dueAt !== undefined) {
    return (
      <span className={overdue ? 'font-medium text-destructive' : undefined}>
        {overdue ? 'Overdue since' : 'Due'} {dateTimeFormat.format(new Date(item.dueAt))}
      </span>
    )
  }
  return (
    <span className="text-muted-foreground">
      {item.kind === 'Acknowledgement' ? 'Published' : 'Since'}{' '}
      {dateFormat.format(new Date(item.createdAt))}
    </span>
  )
}

function Context({ item }: { item: InboxItem }) {
  const parts: string[] = []
  if (item.classPath.length > 0) parts.push(item.classPath.join(' / '))
  if (item.kind === 'Review' || item.kind === 'Approval') {
    parts.push(`${item.task.stepName} (${item.task.role})`)
    const candidates = item.task.candidates ?? []
    // User ids until a directory of display names exists (DMS-91 follow-up).
    if (candidates.length > 0) parts.push(`assigned to ${candidates.join(', ')}`)
  }
  if (item.kind === 'Acknowledgement' && item.acknowledgement.validFrom) {
    parts.push(`valid from ${plainDateFormat.format(new Date(item.acknowledgement.validFrom))}`)
  }
  return parts.length > 0 ? (
    <p className="text-xs break-words text-muted-foreground">{parts.join(' · ')}</p>
  ) : null
}

function AcknowledgementActions({
  ack,
  onDone,
}: {
  ack: Acknowledgement
  onDone: (message: string) => void
}) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const open = useMutation({
    mutationFn: () => markOpened(client, ack.documentId, ack.versionId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['acknowledgements'] })
      await navigate({ to: '/documents/$documentId', params: { documentId: ack.documentId } })
    },
  })
  const confirm = useMutation({
    mutationFn: () => acknowledge(client, ack.documentId, ack.versionId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['acknowledgements'] })
      onDone(`Read confirmed: ${ack.title} ${ack.versionLabel}.`)
    },
  })
  const error = open.error ?? confirm.error
  return (
    <div className="flex flex-col items-start gap-1 sm:items-end">
      <div className="flex gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={open.isPending}
          onClick={() => {
            open.mutate()
          }}
        >
          Open document
        </Button>
        <Button
          size="sm"
          disabled={!ack.opened || confirm.isPending}
          onClick={() => {
            confirm.mutate()
          }}
          aria-describedby={ack.opened ? undefined : `ack-hint-${ack.versionId}`}
        >
          Confirm read
        </Button>
      </div>
      {!ack.opened && (
        <p id={`ack-hint-${ack.versionId}`} className="text-xs text-muted-foreground">
          Open the document first.
        </p>
      )}
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error.message}
        </p>
      )}
    </div>
  )
}

function InboxRow({
  item,
  now,
  onVote,
  onDone,
}: {
  item: InboxItem
  now: number
  onVote: (task: WorkflowTask) => void
  onDone: (message: string) => void
}) {
  const overdue = isOverdue(item, now)
  const title = item.title ?? 'Document'
  return (
    <li
      aria-label={`${kindLabels[item.kind]}: ${title}${overdue ? ' (overdue)' : ''}`}
      data-overdue={overdue || undefined}
      className={`flex flex-col gap-3 rounded-lg border p-3 sm:flex-row sm:items-center sm:justify-between ${
        overdue ? 'border-destructive/60 bg-destructive/5' : ''
      }`}
    >
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant={item.kind === 'Approval' ? 'default' : 'secondary'}>
            {kindLabels[item.kind]}
          </Badge>
          {overdue && <Badge variant="destructive">Overdue</Badge>}
          <Link
            to="/documents/$documentId"
            params={{ documentId: item.documentId }}
            className="font-medium break-words hover:underline"
          >
            {title}
          </Link>
          {item.versionLabel && (
            <span className="text-muted-foreground">version {item.versionLabel}</span>
          )}
        </div>
        <Context item={item} />
        <p className="text-xs">
          <ItemDate item={item} overdue={overdue} />
        </p>
      </div>
      <div className="shrink-0">
        {(item.kind === 'Review' || item.kind === 'Approval') && (
          <Button
            size="sm"
            onClick={() => {
              onVote(item.task)
            }}
          >
            Vote…
          </Button>
        )}
        {item.kind === 'Acknowledgement' && (
          <AcknowledgementActions ack={item.acknowledgement} onDone={onDone} />
        )}
        {item.kind === 'ReviewDate' && (
          <Button size="sm" variant="outline" asChild>
            <Link to="/documents/$documentId" params={{ documentId: item.documentId }}>
              Open document
            </Link>
          </Button>
        )}
      </div>
    </li>
  )
}

/**
 * Central task inbox (DMS-48): open review and approval tasks and read confirmations in one list,
 * overdue first, with the actions next to each task.
 */
export function TaskInboxPage() {
  const client = useApiClient()
  const tasks = useQuery(myTasksQuery(client))
  const acknowledgements = useQuery(openAcknowledgementsQuery(client))
  const documentIds = [...new Set((tasks.data ?? []).map((t) => t.documentId))]
  const documents = useQueries({
    queries: documentIds.map((id) => documentQuery(client, id)),
    combine: (results) =>
      new Map(
        results.flatMap((r, i) => {
          const id = documentIds[i]
          return r.data && id ? [[id, r.data] as [string, DocumentView]] : []
        }),
      ),
  })
  const [voting, setVoting] = useState<{ task: WorkflowTask; initial?: VoteDraft }>()
  const [message, setMessage] = useState('')
  const [mountedAt] = useState(() => Date.now())
  // Overdue is judged against the time the tasks were loaded, so a refetch updates it.
  const now = Math.max(tasks.dataUpdatedAt, mountedAt)

  const items = sortInbox(
    [
      ...(tasks.data ?? []).map((t) => workflowItem(t, documents.get(t.documentId))),
      ...(acknowledgements.data ?? []).map(acknowledgementItem),
    ],
    now,
  )
  const overdueCount = items.filter((i) => isOverdue(i, now)).length
  const titleOf = (task: WorkflowTask) => documents.get(task.documentId)?.title
  const loading = tasks.isPending || acknowledgements.isPending

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="font-heading text-2xl font-semibold">Tasks</h1>
        <p className="text-sm text-muted-foreground">
          Reviews, approvals and read confirmations waiting for you.
        </p>
      </div>
      {tasks.data && (
        <PendingVoteNotice
          tasks={tasks.data}
          titleOf={titleOf}
          onRepeat={(task, pending) => {
            setVoting({ task, initial: { decision: pending.decision, reason: pending.reason } })
          }}
        />
      )}
      <p role="status" className="text-sm empty:hidden">
        {message}
      </p>
      <Card>
        <CardHeader>
          <CardTitle>My tasks</CardTitle>
          <CardDescription>
            {loading
              ? 'Loading…'
              : `${items.length} open${overdueCount > 0 ? `, ${overdueCount} overdue` : ''}`}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {tasks.isError && (
            <p role="alert" className="text-sm text-destructive">
              {tasks.error.message}
            </p>
          )}
          {acknowledgements.isError && (
            <p role="alert" className="text-sm text-destructive">
              {acknowledgements.error.message}
            </p>
          )}
          {loading && <Skeleton className="h-16 w-full" />}
          {!loading && items.length === 0 && !tasks.isError && (
            <p className="text-sm text-muted-foreground">Nothing to do right now.</p>
          )}
          {items.length > 0 && (
            <ul aria-label="Open tasks" className="space-y-2">
              {items.map((item) => (
                <InboxRow
                  key={item.key}
                  item={item}
                  now={now}
                  onVote={(task) => {
                    setVoting({ task })
                  }}
                  onDone={setMessage}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
      <VoteDialog
        task={voting?.task}
        documentTitle={voting && titleOf(voting.task)}
        initial={voting?.initial}
        onClose={() => {
          setVoting(undefined)
        }}
        onVoted={(task, decision) => {
          setVoting(undefined)
          setMessage(
            `Vote recorded: ${decisionLabels[decision]} on ${titleOf(task) ?? 'the document'}.`,
          )
        }}
      />
    </div>
  )
}
