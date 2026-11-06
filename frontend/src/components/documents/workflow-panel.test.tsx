import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { WorkflowCase } from '@/api/documents'
import { setLocale } from '@/i18n'
import { WorkflowPanel } from './workflow-panel'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000e1'
const description = 'The workflow starts when the document is submitted.'

const task = (id: string, step: string, stepName: string, round = 1) => ({
  id,
  round,
  step,
  stepName,
  kind: 'Approval',
  role: 'Approver',
  status: 'Completed',
  candidates: ['approver-1'],
})

const vote = (taskId: string, step: string, round = 1) => ({
  id: `vote-${taskId}`,
  taskId,
  round,
  step,
  lane: 'approver',
  voter: 'approver-1',
  decision: 'Approve',
  contentSha256: 'a'.repeat(64),
  votedAt: '2026-11-02T09:00:00Z',
})

function workflowCase(extra: Partial<WorkflowCase> = {}): WorkflowCase {
  return {
    id: 'c1',
    documentId,
    versionId: 'v1',
    round: 1,
    status: 'Running',
    tasks: [],
    votes: [],
    ...extra,
  } as unknown as WorkflowCase
}

function renderPanel(cases: WorkflowCase[], draftVersion?: string) {
  const get = vi.fn((path: string) =>
    Promise.resolve({
      data: path === '/api/v1/documents/{id}/workflow' ? cases : [],
      response: new Response(null, { status: 200 }),
    }),
  )
  const client = { GET: get } as unknown as ApiClient
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApiClientProvider client={client}>
        <WorkflowPanel documentId={documentId} versions={{}} draftVersion={draftVersion} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  setLocale('en')
})

describe('Votes of a case', () => {
  it('name the step as the tasks do, also the platform’s own and those of earlier rounds', async () => {
    renderPanel([
      workflowCase({
        round: 2,
        tasks: [
          task('t1', 'approval', 'Management approval'),
          task('t2', 'confidentiality-lowering', 'confidentiality-lowering', 2),
        ],
        votes: [vote('t1', 'approval'), vote('t2', 'confidentiality-lowering', 2)],
      } as unknown as Partial<WorkflowCase>),
    ])
    expect(await screen.findByText(/round 1, Management approval/)).toBeInTheDocument()
    expect(screen.getByText(/round 2, Approval of the lower confidentiality/)).toBeInTheDocument()
    expect(screen.queryByText(/round 1, approval\)/)).not.toBeInTheDocument()
  })
})

describe('The description', () => {
  it('shows before the first submission', async () => {
    renderPanel([], 'v1')
    expect(await screen.findByText('Not submitted yet.')).toBeInTheDocument()
    expect(screen.getByText(description)).toBeInTheDocument()
  })

  it('shows for a later draft that has no workflow yet', async () => {
    renderPanel([workflowCase({ status: 'Approved' })], 'v2')
    expect(await screen.findByTestId('workflow-case')).toBeInTheDocument()
    expect(screen.getByText(description)).toBeInTheDocument()
  })

  it('is gone while the workflow runs and once it is finished', async () => {
    renderPanel([workflowCase()])
    expect(await screen.findByTestId('workflow-case')).toBeInTheDocument()
    expect(screen.queryByText(description)).not.toBeInTheDocument()
  })

  it('is gone for a draft that was returned', async () => {
    renderPanel([workflowCase({ status: 'Returned' })], 'v1')
    expect(await screen.findByTestId('workflow-case')).toBeInTheDocument()
    expect(screen.queryByText(description)).not.toBeInTheDocument()
  })
})
