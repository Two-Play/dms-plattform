import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { CheckCircle2 } from 'lucide-react'
import { Fragment, useState, type ReactNode } from 'react'
import {
  acknowledge,
  markOpened,
  openAcknowledgementsQuery,
  type Acknowledgement,
} from '@/api/acknowledgements'
import { useApiClient } from '@/api/context'
import { documentQuery } from '@/api/documents'
import type { DocumentView } from '@/api/partners'
import { openReviewTasksQuery, type ReviewTask } from '@/api/reviews'
import { decisionLabel, myTasksQuery, type WorkflowTask } from '@/api/workflow'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, InlineError } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { roleLabel } from '@/components/shared/role-label'
import { UserName, UserNames } from '@/components/shared/user-name'
import { t } from '@/i18n'
import { formatDate, formatDateTime, formatPlainDate } from '@/i18n/format'
import { toast } from '@/lib/toast'
import {
  acknowledgementItem,
  isOverdue,
  kindLabel,
  reviewItem,
  sortInbox,
  workflowItem,
  type InboxItem,
  type ReviewDecision,
} from './inbox-items'
import { PendingVoteNotice } from './pending-vote-notice'
import { ReviewDialog } from './review-dialog'
import { VoteDialog, type VoteDraft } from './vote-dialog'

function ItemDate({ item, overdue }: { item: InboxItem; overdue: boolean }) {
  if (item.kind === 'ReviewDate') {
    return (
      <span className={overdue ? 'font-medium text-destructive' : undefined}>
        {t(overdue ? 'tasks.reviewOverdueSince' : 'tasks.reviewDue', {
          date: formatPlainDate(item.review.dueOn),
        })}
      </span>
    )
  }
  if (item.dueAt !== undefined) {
    return (
      <span className={overdue ? 'font-medium text-destructive' : undefined}>
        {t(overdue ? 'tasks.overdueSince' : 'tasks.due', { date: formatDateTime(item.dueAt) })}
      </span>
    )
  }
  return (
    <span className="text-muted-foreground">
      {t(item.kind === 'Acknowledgement' ? 'tasks.publishedOn' : 'tasks.since', {
        date: formatDate(item.createdAt),
      })}
    </span>
  )
}

function Context({ item }: { item: InboxItem }) {
  const parts: ReactNode[] = []
  if (item.classPath.length > 0) parts.push(item.classPath.join(' / '))
  if (item.kind === 'Review' || item.kind === 'Approval') {
    parts.push(`${item.task.stepName} (${roleLabel(item.task.role)})`)
    const candidates = item.task.candidates ?? []
    if (candidates.length > 0) {
      parts.push(
        <>
          {t('tasks.assignedTo')} <UserNames ids={candidates} />
        </>,
      )
    }
  }
  if (item.kind === 'ReviewDate') {
    parts.push(
      item.review.versionStatus === 'ReviewDue'
        ? t('tasks.reviewDateReached')
        : t('tasks.reviewAhead'),
    )
  }
  if (item.kind === 'Acknowledgement' && item.acknowledgement.validFrom) {
    parts.push(t('tasks.validFrom', { date: formatPlainDate(item.acknowledgement.validFrom) }))
  }
  return parts.length > 0 ? (
    <p className="text-xs break-words text-muted-foreground">
      {parts.map((part, i) => (
        <Fragment key={i}>
          {i > 0 && ' · '}
          {part}
        </Fragment>
      ))}
    </p>
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
      onDone(t('tasks.readConfirmed', { title: ack.title, version: ack.versionLabel }))
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
          {t('tasks.openDocument')}
        </Button>
        <Button
          size="sm"
          disabled={!ack.opened || confirm.isPending}
          onClick={() => {
            confirm.mutate()
          }}
          aria-describedby={ack.opened ? undefined : `ack-hint-${ack.versionId}`}
        >
          {t('tasks.confirmRead')}
        </Button>
      </div>
      {!ack.opened && (
        <p id={`ack-hint-${ack.versionId}`} className="text-xs text-muted-foreground">
          {t('tasks.openFirst')}
        </p>
      )}
      {error && <InlineError error={error} className="text-xs" />}
    </div>
  )
}

function ReviewActions({
  review,
  onDecide,
}: {
  review: ReviewTask
  onDecide: (review: ReviewTask, decision: ReviewDecision) => void
}) {
  return (
    <div className="flex flex-col items-start gap-1 sm:items-end">
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          onClick={() => {
            onDecide(review, 'recertify')
          }}
        >
          {t('tasks.recertify')}
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            onDecide(review, 'revise')
          }}
        >
          {t('tasks.revise')}
        </Button>
        <Button
          size="sm"
          variant="destructive"
          onClick={() => {
            onDecide(review, 'withdraw')
          }}
        >
          {t('tasks.withdraw')}
        </Button>
      </div>
    </div>
  )
}

function InboxRow({
  item,
  now,
  onVote,
  onReview,
  onDone,
}: {
  item: InboxItem
  now: number
  onVote: (task: WorkflowTask) => void
  onReview: (review: ReviewTask, decision: ReviewDecision) => void
  onDone: (message: string) => void
}) {
  const overdue = isOverdue(item, now)
  const title = item.title ?? t('common.document')
  return (
    <li
      aria-label={t(overdue ? 'tasks.itemLabelOverdue' : 'tasks.itemLabel', {
        kind: kindLabel(item.kind),
        title,
      })}
      data-overdue={overdue || undefined}
      className={`flex flex-col gap-3 rounded-lg border p-3 sm:flex-row sm:items-start sm:justify-between ${
        overdue ? 'border-destructive/60 bg-destructive/5' : ''
      }`}
    >
      {/* Same layout for every task: badges, title, context, date; actions right (bottom on mobile). */}
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={item.kind === 'Approval' ? 'default' : 'secondary'}>
            {kindLabel(item.kind)}
          </Badge>
          {overdue && <Badge variant="destructive">{t('common.overdue')}</Badge>}
          {(item.kind === 'Review' || item.kind === 'Approval') && item.task.actingFor && (
            // Forwarded by an active deputyship (DMS-49, DMS-99).
            <Badge variant="outline">
              {t('tasks.onBehalfOf')} <UserName id={item.task.actingFor} />
            </Badge>
          )}
        </div>
        <p className="text-sm">
          <Link
            to="/documents/$documentId"
            params={{ documentId: item.documentId }}
            className="font-medium break-words underline-offset-4 hover:underline"
          >
            {title}
          </Link>
          {item.versionLabel && (
            <span className="text-muted-foreground">
              {' '}
              {t('tasks.versionSuffix', { label: item.versionLabel })}
            </span>
          )}
        </p>
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
            {t('tasks.vote')}
          </Button>
        )}
        {item.kind === 'Acknowledgement' && (
          <AcknowledgementActions ack={item.acknowledgement} onDone={onDone} />
        )}
        {item.kind === 'ReviewDate' && <ReviewActions review={item.review} onDecide={onReview} />}
      </div>
    </li>
  )
}

/**
 * Central task inbox (DMS-48): open review and approval tasks, read confirmations and periodic
 * reviews of the caller's documents (DMS-55) in one list, overdue first, with the actions next to
 * each task.
 */
export function TaskInboxPage() {
  const client = useApiClient()
  const tasks = useQuery(myTasksQuery(client))
  const acknowledgements = useQuery(openAcknowledgementsQuery(client))
  const reviews = useQuery(openReviewTasksQuery(client))
  const documentIds = [...new Set((tasks.data ?? []).map((task) => task.documentId))]
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
  const [deciding, setDeciding] = useState<{ review: ReviewTask; decision: ReviewDecision }>()
  const [mountedAt] = useState(() => Date.now())
  // Overdue is judged against the time the tasks were loaded, so a refetch updates it.
  const now = Math.max(tasks.dataUpdatedAt, mountedAt)

  const items = sortInbox(
    [
      ...(tasks.data ?? []).map((task) => workflowItem(task, documents.get(task.documentId))),
      ...(acknowledgements.data ?? []).map(acknowledgementItem),
      ...(reviews.data ?? []).map(reviewItem),
    ],
    now,
  )
  const overdueCount = items.filter((i) => isOverdue(i, now)).length
  const titleOf = (task: WorkflowTask) => documents.get(task.documentId)?.title
  const loading = tasks.isPending || acknowledgements.isPending || reviews.isPending

  return (
    <div className="space-y-6">
      <PageHeader title={t('tasks.title')} description={t('tasks.description')} />
      {tasks.data && (
        <PendingVoteNotice
          tasks={tasks.data}
          titleOf={titleOf}
          onRepeat={(task, pending) => {
            setVoting({ task, initial: { decision: pending.decision, reason: pending.reason } })
          }}
        />
      )}
      <Card>
        <CardHeader>
          <CardTitle>{t('tasks.myTasks')}</CardTitle>
          <CardDescription>
            {loading
              ? t('common.loading')
              : overdueCount > 0
                ? t('tasks.summaryOverdue', {
                    open: t('tasks.summary', { count: items.length }),
                    overdue: overdueCount,
                  })
                : t('tasks.summary', { count: items.length })}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {tasks.isError && <ErrorState error={tasks.error} onRetry={() => void tasks.refetch()} />}
          {acknowledgements.isError && (
            <ErrorState
              error={acknowledgements.error}
              onRetry={() => void acknowledgements.refetch()}
            />
          )}
          {reviews.isError && (
            <ErrorState error={reviews.error} onRetry={() => void reviews.refetch()} />
          )}
          {loading && <LoadingState label={t('tasks.loading')} />}
          {!loading && items.length === 0 && !tasks.isError && (
            <EmptyState
              icon={CheckCircle2}
              title={t('tasks.emptyTitle')}
              description={t('tasks.emptyText')}
            />
          )}
          {items.length > 0 && (
            <ul aria-label={t('tasks.listLabel')} className="space-y-2">
              {items.map((item) => (
                <InboxRow
                  key={item.key}
                  item={item}
                  now={now}
                  onVote={(task) => {
                    setVoting({ task })
                  }}
                  onReview={(review, decision) => {
                    setDeciding({ review, decision })
                  }}
                  onDone={toast}
                />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
      <ReviewDialog
        task={deciding?.review}
        decision={deciding?.decision ?? 'recertify'}
        onClose={() => {
          setDeciding(undefined)
        }}
        onDone={(review) => {
          setDeciding(undefined)
          toast(
            review.status === 'Revised'
              ? t('tasks.revisionStarted', { title: review.title })
              : review.status === 'Recertified'
                ? t('tasks.recertified', {
                    title: review.title,
                    version: review.versionLabel,
                    date: formatPlainDate(review.nextDueOn ?? review.dueOn),
                  })
                : t('tasks.withdrawn', { title: review.title, version: review.versionLabel }),
          )
        }}
      />
      <VoteDialog
        task={voting?.task}
        documentTitle={voting && titleOf(voting.task)}
        initial={voting?.initial}
        onClose={() => {
          setVoting(undefined)
        }}
        onVoted={(task, decision) => {
          setVoting(undefined)
          toast(
            t('tasks.voteRecorded', {
              decision: decisionLabel(decision),
              title: titleOf(task) ?? t('tasks.theDocument'),
            }),
          )
        }}
      />
    </div>
  )
}
