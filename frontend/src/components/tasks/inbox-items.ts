import type { Acknowledgement } from '@/api/acknowledgements'
import type { DocumentView } from '@/api/partners'
import type { ReviewTask } from '@/api/reviews'
import type { WorkflowTask } from '@/api/workflow'
import { t } from '@/i18n'

/**
 * Kinds of tasks in the inbox. `ReviewDate` is the periodic review of a valid version by the
 * document owner (DMS-55): recertify, revise or withdraw.
 */
export type InboxKind = 'Review' | 'Approval' | 'Acknowledgement' | 'ReviewDate'

interface InboxItemBase {
  /** Unique within the inbox. */
  key: string
  documentId: string
  /** Document title; undefined while the document is still loading or could not be read. */
  title: string | undefined
  classPath: string[]
  versionLabel: string | undefined
  /** When the task arose. */
  createdAt: string
  /** Deadline, if the task has one. */
  dueAt: string | undefined
}

export type InboxItem =
  | (InboxItemBase & { kind: 'Review' | 'Approval'; task: WorkflowTask })
  | (InboxItemBase & { kind: 'Acknowledgement'; acknowledgement: Acknowledgement })
  | (InboxItemBase & { kind: 'ReviewDate'; review: ReviewTask })

export function kindLabel(kind: InboxKind): string {
  return t(`tasks.kinds.${kind}`)
}

/** An inbox item for a workflow task, with the document's title and version if it is loaded. */
export function workflowItem(task: WorkflowTask, document: DocumentView | undefined): InboxItem {
  return {
    key: `task:${task.id}`,
    kind: task.kind === 'Approval' ? 'Approval' : 'Review',
    task,
    documentId: task.documentId,
    title: document?.title,
    classPath: document?.classPath ?? [],
    versionLabel: document?.current.id === task.versionId ? document.current.version : undefined,
    createdAt: task.createdAt,
    dueAt: task.dueAt,
  }
}

export function acknowledgementItem(ack: Acknowledgement): InboxItem {
  return {
    key: `ack:${ack.documentId}:${ack.versionId}`,
    kind: 'Acknowledgement',
    acknowledgement: ack,
    documentId: ack.documentId,
    title: ack.title,
    classPath: ack.classPath ?? [],
    versionLabel: ack.versionLabel,
    createdAt: ack.publishedAt ?? ack.validFrom ?? '',
    dueAt: undefined,
  }
}

/**
 * The decisions of a review task that need input: recertify (date, note), revise (change summary of the new
 * draft, DMS-126) or withdraw (reason).
 */
export type ReviewDecision = 'recertify' | 'revise' | 'withdraw'

/** An inbox item for a review task; its deadline is the review date (00:00 UTC of that day). */
export function reviewItem(review: ReviewTask): InboxItem {
  return {
    key: `review:${review.id}`,
    kind: 'ReviewDate',
    review,
    documentId: review.documentId,
    title: review.title,
    classPath: review.classPath ?? [],
    versionLabel: review.versionLabel,
    createdAt: review.createdAt,
    dueAt: `${review.dueOn}T00:00:00Z`,
  }
}

export function isOverdue(item: Pick<InboxItem, 'dueAt'>, now: number): boolean {
  return item.dueAt !== undefined && Date.parse(item.dueAt) < now
}

function tier(item: InboxItem, now: number): number {
  if (isOverdue(item, now)) return 0
  return item.dueAt !== undefined ? 1 : 2
}

/**
 * Inbox order: overdue tasks first, then tasks with a deadline (earliest first), then tasks without
 * one (oldest first).
 */
export function sortInbox(items: readonly InboxItem[], now: number): InboxItem[] {
  return items.slice().sort((a, b) => {
    const byTier = tier(a, now) - tier(b, now)
    if (byTier !== 0) return byTier
    const byDate =
      Date.parse(a.dueAt ?? a.createdAt) - Date.parse(b.dueAt ?? b.createdAt) ||
      Date.parse(a.createdAt) - Date.parse(b.createdAt)
    return byDate !== 0 ? byDate : a.key.localeCompare(b.key)
  })
}
