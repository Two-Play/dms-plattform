import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearPendingVote,
  loadPendingVote,
  PENDING_VOTE_MAX_AGE_MS,
  savePendingVote,
} from './pending-vote'

const vote = { taskId: 't1', documentId: 'd1', decision: 'Approve' as const, reason: '' }

describe('pending vote', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('remembers a vote across page loads in the same tab', () => {
    savePendingVote(vote, 1000)
    expect(loadPendingVote(2000)).toEqual({ ...vote, savedAt: 1000 })
    clearPendingVote()
    expect(loadPendingVote(2000)).toBeUndefined()
  })

  it('drops a vote that is too old', () => {
    savePendingVote(vote, 1000)
    expect(loadPendingVote(1000 + PENDING_VOTE_MAX_AGE_MS + 1)).toBeUndefined()
    expect(window.sessionStorage.length).toBe(0)
  })

  it('ignores malformed entries', () => {
    window.sessionStorage.setItem('dms.pendingVote', '{"taskId":1}')
    expect(loadPendingVote()).toBeUndefined()
    window.sessionStorage.setItem('dms.pendingVote', 'not json')
    expect(loadPendingVote()).toBeUndefined()
  })
})
