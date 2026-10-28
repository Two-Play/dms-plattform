import type { VoteDecision } from '@/api/workflow'

/**
 * A vote that was rejected with `reauthentication_required`. It is kept in `sessionStorage` (same tab,
 * survives the sign-in redirect) so the page the user returns to can offer to repeat it; it is never
 * sent automatically (ADR 0024, addendum DMS-86).
 */
export interface PendingVote {
  taskId: string
  documentId: string
  decision: VoteDecision
  reason: string
  /** Epoch milliseconds when the vote was attempted. */
  savedAt: number
}

const KEY = 'dms.pendingVote'

/** A pending vote older than this is dropped: the user has moved on. */
export const PENDING_VOTE_MAX_AGE_MS = 30 * 60 * 1000

const decisions: readonly string[] = [
  'Approve',
  'ApproveWithConditions',
  'RequestChanges',
  'Reject',
]

function isPendingVote(value: unknown): value is PendingVote {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.taskId === 'string' &&
    typeof v.documentId === 'string' &&
    typeof v.decision === 'string' &&
    decisions.includes(v.decision) &&
    typeof v.reason === 'string' &&
    typeof v.savedAt === 'number'
  )
}

export function savePendingVote(vote: Omit<PendingVote, 'savedAt'>, now = Date.now()): void {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify({ ...vote, savedAt: now }))
  } catch {
    // Storage unavailable (private mode, quota): the user simply votes again from scratch.
  }
}

/** The remembered vote, if there is one and it is recent. */
export function loadPendingVote(now = Date.now()): PendingVote | undefined {
  try {
    const raw = window.sessionStorage.getItem(KEY)
    if (!raw) return undefined
    const parsed: unknown = JSON.parse(raw)
    if (!isPendingVote(parsed) || now - parsed.savedAt > PENDING_VOTE_MAX_AGE_MS) {
      window.sessionStorage.removeItem(KEY)
      return undefined
    }
    return parsed
  } catch {
    return undefined
  }
}

export function clearPendingVote(): void {
  try {
    window.sessionStorage.removeItem(KEY)
  } catch {
    // ignore
  }
}
