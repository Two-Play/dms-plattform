import { describe, expect, it } from 'vitest'
import type { WorkflowTask } from '@/api/workflow'
import { acknowledgementItem, isOverdue, sortInbox, workflowItem } from './inbox-items'

const now = Date.parse('2026-10-28T12:00:00Z')

function task(id: string, createdAt: string, dueAt?: string): WorkflowTask {
  return {
    id,
    caseId: 'c',
    documentId: `d-${id}`,
    versionId: 'v',
    round: 1,
    step: 'review',
    stepName: 'Review',
    kind: 'Review',
    lane: 'reviewer',
    role: 'Reviewer',
    status: 'Open',
    createdAt,
    dueAt,
  }
}

describe('inbox items', () => {
  it('marks only tasks with a past deadline as overdue', () => {
    expect(isOverdue({ dueAt: '2026-10-27T12:00:00Z' }, now)).toBe(true)
    expect(isOverdue({ dueAt: '2026-10-29T12:00:00Z' }, now)).toBe(false)
    expect(isOverdue({ dueAt: undefined }, now)).toBe(false)
  })

  it('sorts overdue first, then by deadline, then the oldest tasks without a deadline', () => {
    const items = [
      workflowItem(task('no-due-new', '2026-10-27T00:00:00Z'), undefined),
      workflowItem(task('due-later', '2026-10-01T00:00:00Z', '2026-11-10T00:00:00Z'), undefined),
      workflowItem(task('overdue', '2026-10-20T00:00:00Z', '2026-10-25T00:00:00Z'), undefined),
      workflowItem(task('no-due-old', '2026-10-02T00:00:00Z'), undefined),
      workflowItem(task('due-soon', '2026-10-26T00:00:00Z', '2026-10-30T00:00:00Z'), undefined),
      acknowledgementItem({
        documentId: 'd-ack',
        versionId: 'v-ack',
        title: 'Policy',
        versionLabel: '2.0',
        publishedAt: '2026-10-10T00:00:00Z',
        opened: false,
      }),
    ]

    expect(sortInbox(items, now).map((i) => i.key)).toEqual([
      'task:overdue',
      'task:due-soon',
      'task:due-later',
      'task:no-due-old',
      'ack:d-ack:v-ack',
      'task:no-due-new',
    ])
  })

  it('takes title and version from the document only if the task is on its current version', () => {
    const t = task('t', '2026-10-27T00:00:00Z')
    const doc = {
      title: 'Travel policy',
      classPath: ['policy'],
      current: { id: 'v', version: '2.0-draft.1' },
    } as Parameters<typeof workflowItem>[1]
    expect(workflowItem(t, doc)).toMatchObject({
      title: 'Travel policy',
      classPath: ['policy'],
      versionLabel: '2.0-draft.1',
      kind: 'Review',
    })
    expect(workflowItem({ ...t, versionId: 'other' }, doc).versionLabel).toBeUndefined()
  })
})
