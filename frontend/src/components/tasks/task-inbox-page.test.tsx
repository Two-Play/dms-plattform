import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Acknowledgement } from '@/api/acknowledgements'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Me } from '@/api/me'
import type { DocumentView } from '@/api/partners'
import type { ReviewTask } from '@/api/reviews'
import type { WorkflowTask } from '@/api/workflow'
import { loadPendingVote } from '@/auth/pending-vote'
import { createAppRouter } from '@/router'

const signinRedirect = vi.fn(() => Promise.resolve())
const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'reviewer-1', name: 'Rita Reviewer' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect,
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const policyId = '7c1e2f0a-0000-4000-8000-0000000000a1'
const contractId = '7c1e2f0a-0000-4000-8000-0000000000a2'
const handbookId = '7c1e2f0a-0000-4000-8000-0000000000a3'

function makeDocument(id: string, title: string, versionId: string, version: string): DocumentView {
  return {
    id,
    classId: 'class',
    classVersion: 1,
    classPath: ['policy', 'hr'],
    title,
    authorId: 'author-1',
    templateId: 'tpl',
    templateVersion: 1,
    createdAt: '2026-10-01T10:00:00Z',
    legalHold: false,
    canAssignNumber: false,
    current: {
      id: versionId,
      version,
      workingState: 1,
      status: 'InReview',
      changeKind: 'Major',
      requiresAcknowledgement: false,
      scope: '',
      classVersion: 1,
      templateId: 'tpl',
      templateVersion: 1,
      content: {},
      values: {},
      createdBy: 'author-1',
      createdAt: '2026-10-01T10:00:00Z',
      updatedAt: '2026-10-01T10:00:00Z',
      requiresExtraReview: false,
    },
  }
}

const documents: Record<string, DocumentView> = {
  [policyId]: makeDocument(policyId, 'Travel policy', 'vp', '2.0-draft.1'),
  [contractId]: makeDocument(contractId, 'Service contract ACME', 'vc', '1.0-draft.3'),
  [handbookId]: makeDocument(handbookId, 'Handbook', 'vh', '3.0'),
}

function task(over: Partial<WorkflowTask> & Pick<WorkflowTask, 'id' | 'documentId'>): WorkflowTask {
  return {
    caseId: 'case',
    versionId: over.documentId === policyId ? 'vp' : 'vc',
    round: 1,
    step: 'review',
    stepName: 'Legal review',
    kind: 'Review',
    lane: 'legal',
    role: 'Reviewer',
    candidates: ['reviewer-1', 'reviewer-2'],
    status: 'Open',
    createdAt: '2026-10-20T10:00:00Z',
    ...over,
  }
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface State {
  me: Me
  tasks: WorkflowTask[]
  acks: Acknowledgement[] | 'missing'
  reviews: ReviewTask[]
  voteReplies: (() => Reply)[]
}

const review: ReviewTask = {
  id: 'review-handbook',
  documentId: handbookId,
  title: 'Handbook',
  classPath: ['policy'],
  versionId: 'vh',
  versionLabel: '3.0',
  versionStatus: 'Published',
  status: 'Open',
  dueOn: '2099-01-01',
  createdAt: '2026-10-27T23:00:00Z',
  reviewIntervalMonths: 12,
}

let state: State

beforeEach(() => {
  window.sessionStorage.clear()
  signinRedirect.mockClear()
  state = {
    me: { userId: 'reviewer-1', tenantId: 'tenant-1', roles: ['Reviewer'] },
    tasks: [
      task({ id: 'review-contract', documentId: contractId, createdAt: '2026-10-10T10:00:00Z' }),
      task({
        id: 'approve-policy',
        documentId: policyId,
        kind: 'Approval',
        step: 'approval',
        stepName: 'Approval',
        role: 'Approver',
        createdAt: '2026-10-25T10:00:00Z',
        dueAt: '2020-01-01T00:00:00Z',
      }),
    ],
    acks: [],
    reviews: [],
    voteReplies: [],
  }
})

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: { params?: { path?: { id?: string } } }) => {
    switch (p) {
      case '/api/v1/me':
        return ok(state.me)
      case '/api/v1/workflow/tasks':
        return ok(state.tasks)
      case '/api/v1/acknowledgements':
        return state.acks === 'missing'
          ? failed(404, { code: 'not_found', message: 'Not found' })
          : ok(state.acks)
      case '/api/v1/reviews':
        return ok(state.reviews)
      case '/api/v1/documents/{id}':
        return ok(documents[init?.params?.path?.id ?? ''])
      case '/api/v1/documents/{id}/workflow':
        return ok([])
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: { params?: { path?: Record<string, string> } }) => {
    const decided = (status: string, extra: Partial<ReviewTask>) => {
      const task = state.reviews.find((r) => r.id === init?.params?.path?.taskId)
      state.reviews = state.reviews.filter((r) => r.id !== init?.params?.path?.taskId)
      return ok({ ...task, status, ...extra })
    }
    if (p === '/api/v1/reviews/{taskId}/recertify') {
      return decided('Recertified', { nextDueOn: '2100-01-01' })
    }
    if (p === '/api/v1/reviews/{taskId}/withdraw') {
      return decided('Withdrawn', { versionStatus: 'Withdrawn' })
    }
    if (p === '/api/v1/reviews/{taskId}/revise') {
      return decided('Revised', { newVersionId: 'vh-next' })
    }
    if (p === '/api/v1/workflow/tasks/{taskId}/votes') {
      const reply = state.voteReplies.shift()
      if (reply) return reply()
      state.tasks = state.tasks.filter((t) => t.id !== init?.params?.path?.taskId)
      return ok({ id: 'case' })
    }
    if (p === '/api/v1/documents/{id}/versions/{versionId}/acknowledgement') {
      state.acks = []
      return ok(
        {
          documentId: init?.params?.path?.id,
          versionId: init?.params?.path?.versionId,
          versionLabel: '3.0',
          openedAt: '2026-10-27T09:00:00Z',
          acknowledgedAt: '2026-10-27T09:05:00Z',
        },
        201,
      )
    }
    if (p === '/api/v1/documents/{id}/versions/{versionId}/opened') return ok(undefined, 204)
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  const result = render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, user: userEvent.setup(), unmount: result.unmount }
}

function must<T>(value: T | undefined): T {
  if (value === undefined) throw new Error('missing')
  return value
}

const taskList = () => screen.findByRole('list', { name: 'Open tasks' })

async function rows(): Promise<HTMLElement[]> {
  const list = await taskList()
  await within(list).findAllByText('Travel policy')
  return within(list).getAllByRole('listitem')
}

describe('task inbox', () => {
  it('lists the tasks with their document, overdue ones first and highlighted', async () => {
    renderAt('/tasks')

    const [first, second] = await rows()
    expect(first).toHaveAccessibleName('Approval: Travel policy (overdue)')
    expect(first).toHaveAttribute('data-overdue', 'true')
    expect(within(must(first)).getByText('Overdue')).toBeInTheDocument()
    expect(within(must(first)).getByText(/Overdue since/)).toBeInTheDocument()
    expect(within(must(first)).getByText('version 2.0-draft.1')).toBeInTheDocument()

    expect(second).toHaveAccessibleName('Review: Service contract ACME')
    expect(second).not.toHaveAttribute('data-overdue')
    // Assignees are user names (ids until the user directory answers), so the line spans elements.
    expect(within(must(second)).getByText(/Legal review \(Reviewer\)/)).toHaveTextContent(
      'policy / hr · Legal review (Reviewer) · assigned to reviewer-1, reviewer-2',
    )
    expect(within(must(second)).getByText(/^Since /)).toBeInTheDocument()
    expect(screen.getByText('2 open, 1 overdue')).toBeInTheDocument()
  })

  it('marks tasks forwarded by a deputyship with the principal (DMS-99)', async () => {
    state.tasks = state.tasks.map((t) =>
      t.id === 'approve-policy' ? { ...t, actingFor: 'approver-1' } : t,
    )
    renderAt('/tasks')

    const [first, second] = await rows()
    expect(within(must(first)).getByText(/On behalf of/)).toHaveTextContent(
      'On behalf of approver-1',
    )
    expect(within(must(second)).queryByText(/On behalf of/)).not.toBeInTheDocument()
  })

  it('requires a reason for every decision except a plain approval', async () => {
    const { post, user } = renderAt('/tasks')
    const [, review] = await rows()

    await user.click(within(must(review)).getByRole('button', { name: 'Vote…' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Reject')).not.toBeInTheDocument()
    await user.click(within(dialog).getByLabelText('Request changes'))
    await user.click(within(dialog).getByRole('button', { name: 'Cast vote' }))

    expect(within(dialog).getByText('A reason is required for this decision.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Reason/)).toHaveAttribute('aria-invalid', 'true')
    expect(post).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText(/^Reason/), 'Clause 4 is unclear')
    await user.click(within(dialog).getByRole('button', { name: 'Cast vote' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith('/api/v1/workflow/tasks/{taskId}/votes', {
      params: { path: { taskId: 'review-contract' } },
      body: { decision: 'RequestChanges', reason: 'Clause 4 is unclear' },
    })
    expect(
      await screen.findByText('Vote recorded: Request changes on Service contract ACME.'),
    ).toBeInTheDocument()
    expect(await screen.findByText('1 open, 1 overdue')).toBeInTheDocument()
  })

  it('asks for a fresh sign-in when approving needs it and offers to repeat the vote afterwards', async () => {
    state.voteReplies.push(() =>
      failed(401, { code: 'reauthentication_required', message: 'Sign in again' }),
    )
    const first = renderAt('/tasks')
    const [approval] = await rows()

    await first.user.click(within(must(approval)).getByRole('button', { name: 'Vote…' }))
    const dialog = await screen.findByRole('dialog')
    await first.user.click(within(dialog).getByLabelText('Approve with conditions'))
    await first.user.type(within(dialog).getByLabelText(/^Reason/), 'Update the annex')
    await first.user.click(within(dialog).getByRole('button', { name: 'Cast vote' }))

    expect(await within(dialog).findByText(/Your vote was not recorded/)).toBeInTheDocument()
    expect(loadPendingVote()).toMatchObject({
      taskId: 'approve-policy',
      documentId: policyId,
      decision: 'ApproveWithConditions',
      reason: 'Update the annex',
    })
    await first.user.click(within(dialog).getByRole('button', { name: 'Sign in again' }))
    expect(signinRedirect).toHaveBeenCalledWith(
      expect.objectContaining({ prompt: 'login', max_age: 0 }),
    )

    // Back from the sign-in: a new page load of the inbox.
    first.unmount()
    const second = renderAt('/tasks')
    const notice = await screen.findByText(/was not recorded because a fresh sign-in was needed/)
    expect(notice).toHaveTextContent('Approve with conditions')
    expect(notice).toHaveTextContent('Travel policy')
    await second.user.click(screen.getByRole('button', { name: 'Repeat vote' }))

    const repeated = await screen.findByRole('dialog')
    expect(within(repeated).getByLabelText('Approve with conditions')).toBeChecked()
    expect(within(repeated).getByLabelText(/^Reason/)).toHaveValue('Update the annex')
    await second.user.click(within(repeated).getByRole('button', { name: 'Cast vote' }))

    await waitFor(() => {
      expect(second.post).toHaveBeenCalledWith('/api/v1/workflow/tasks/{taskId}/votes', {
        params: { path: { taskId: 'approve-policy' } },
        body: { decision: 'ApproveWithConditions', reason: 'Update the annex' },
      })
    })
    await waitFor(() => {
      expect(loadPendingVote()).toBeUndefined()
    })
  })

  it('lets the user open a published version and then confirm reading it', async () => {
    state.tasks = []
    state.acks = [
      {
        documentId: handbookId,
        versionId: 'vh',
        title: 'Handbook',
        versionLabel: '3.0',
        classPath: ['policy'],
        validFrom: '2026-11-01',
        publishedAt: '2026-10-26T10:00:00Z',
        status: 'Published',
        opened: false,
      },
    ]
    const { post, user, router } = renderAt('/tasks')
    const list = await taskList()
    const row = within(list).getByRole('listitem', { name: 'Read confirmation: Handbook' })
    expect(within(row).getByRole('button', { name: 'Confirm read' })).toBeDisabled()
    expect(within(row).getByText('Open the document first.')).toBeInTheDocument()

    await user.click(within(row).getByRole('button', { name: 'Open document' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/documents/${handbookId}`)
    })
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/opened', {
      params: { path: { id: handbookId, versionId: 'vh' } },
    })

    const ack = state.acks[0]
    if (ack) ack.opened = true
    await router.navigate({ to: '/tasks' })
    const opened = await screen.findByRole('listitem', { name: 'Read confirmation: Handbook' })
    await user.click(within(opened).getByRole('button', { name: 'Confirm read' }))

    expect(await screen.findByText('Read confirmed: Handbook 3.0.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/acknowledgement',
      { params: { path: { id: handbookId, versionId: 'vh' } } },
    )
    expect(await screen.findByText('Nothing to do right now.')).toBeInTheDocument()
  })

  it('leaves read confirmations out while the backend does not offer them', async () => {
    state.acks = 'missing'
    renderAt('/tasks')

    expect(await rows()).toHaveLength(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the periodic review of an owned document with recertify, revise and withdraw', async () => {
    state.tasks = []
    state.reviews = [
      review,
      { ...review, id: 'review-overdue', dueOn: '2020-01-01', title: 'Code' },
    ]
    renderAt('/tasks')

    const list = await taskList()
    const overdue = await within(list).findByRole('listitem', {
      name: 'Periodic review: Code (overdue)',
    })
    const row = within(list).getByRole('listitem', { name: 'Periodic review: Handbook' })
    expect(within(list).getAllByRole('listitem')[0]).toBe(overdue)
    expect(within(overdue).getByText(/Review overdue since/)).toBeInTheDocument()
    expect(within(row).getByText(/^Review due /)).toBeInTheDocument()
    expect(within(row).getByText('version 3.0')).toBeInTheDocument()
    expect(within(row).getByText('policy · valid, review ahead')).toBeInTheDocument()
    for (const name of ['Recertify…', 'Revise', 'Withdraw…']) {
      expect(within(row).getByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.getByText('2 open, 1 overdue')).toBeInTheDocument()
  })

  it('recertifies with the default date of the class interval or a chosen one', async () => {
    state.tasks = []
    state.reviews = [review]
    const { post, user } = renderAt('/tasks')

    const row = await screen.findByRole('listitem', { name: 'Periodic review: Handbook' })
    await user.click(within(row).getByRole('button', { name: 'Recertify…' }))
    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByText(/today plus the class's review interval \(12 months\)/),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/^Next review date/), '2100-01-01')
    await user.type(within(dialog).getByLabelText(/^Note/), 'Still accurate')
    await user.click(within(dialog).getByRole('button', { name: 'Recertify' }))

    expect(await screen.findByText(/^Recertified: Handbook 3.0, next review/)).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/{taskId}/recertify', {
      params: { path: { taskId: 'review-handbook' } },
      body: { nextReviewOn: '2100-01-01', comment: 'Still accurate' },
    })
    expect(await screen.findByText('Nothing to do right now.')).toBeInTheDocument()
  })

  it('withdraws only with a reason', async () => {
    state.tasks = []
    state.reviews = [review]
    const { post, user } = renderAt('/tasks')

    const row = await screen.findByRole('listitem', { name: 'Periodic review: Handbook' })
    await user.click(within(row).getByRole('button', { name: 'Withdraw…' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))
    expect(within(dialog).getByText('A reason is required.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText(/^Reason/), 'Replaced by the group policy')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))

    expect(
      await screen.findByText('Withdrawn: Handbook 3.0 is no longer in force.'),
    ).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/{taskId}/withdraw', {
      params: { path: { taskId: 'review-handbook' } },
      body: { reason: 'Replaced by the group policy' },
    })
  })

  it('starts the revision and opens the document', async () => {
    state.tasks = []
    state.reviews = [review]
    const { post, user, router } = renderAt('/tasks')

    const row = await screen.findByRole('listitem', { name: 'Periodic review: Handbook' })
    await user.click(within(row).getByRole('button', { name: 'Revise' }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/documents/${handbookId}`)
    })
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/{taskId}/revise', {
      params: { path: { taskId: 'review-handbook' } },
      body: {},
    })
  })

  it('lets a participant vote from the document page', async () => {
    const { user, post } = renderAt(`/documents/${contractId}`)

    await user.click(await screen.findByRole('button', { name: 'Vote…' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Legal review: Service contract ACME')).toBeInTheDocument()
    await user.click(within(dialog).getByLabelText('Approve'))
    await user.click(within(dialog).getByRole('button', { name: 'Cast vote' }))

    expect(await screen.findByText('Vote recorded: Approve.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/workflow/tasks/{taskId}/votes', {
      params: { path: { taskId: 'review-contract' } },
      body: { decision: 'Approve', reason: undefined },
    })
  })
})

describe('navigation by role', () => {
  const navLinks = async () => {
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await within(nav).findByRole('link', { name: 'Tasks' })
    return nav
  }

  it('shows Tasks to everyone but Templates and Admin only to their roles', async () => {
    renderAt('/tasks')
    const nav = await navLinks()
    await waitFor(() => {
      expect(within(nav).queryByRole('link', { name: 'Templates' })).not.toBeInTheDocument()
    })
    expect(within(nav).queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
  })

  it('shows Templates to template designers and Admin to tenant administrators', async () => {
    state.me = { ...state.me, roles: ['TemplateDesigner', 'TenantAdmin'] }
    renderAt('/tasks')
    const nav = await navLinks()

    expect(await within(nav).findByRole('link', { name: 'Templates' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Admin' })).toBeInTheDocument()
  })
})
