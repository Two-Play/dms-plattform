import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentSummary, FieldValue, WorkflowCase } from '@/api/documents'
import type { DocumentView } from '@/api/partners'
import type { TemplateFields } from '@/api/templates'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'author-1', name: 'Ada Author' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const reviewedId = '7c1e2f0a-0000-4000-8000-0000000000d2'

const fields: TemplateFields = {
  templateId,
  version: 1,
  name: 'Service contract',
  documentClassId: '7c1e2f0a-0000-4000-8000-000000000002',
  documentClassVersion: 1,
  fields: [
    {
      key: 'title',
      label: 'Title',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: false,
    },
    {
      key: 'termMonths',
      label: 'Term in months',
      fieldType: { type: 'Number', min: 1, max: 120 },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
    {
      key: 'contractPartner',
      label: 'Contract partner',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: true,
      reference: { reference: 'contractPartner', attribute: 'name' },
    },
  ],
  references: [
    {
      key: 'contractPartner',
      label: 'Contract partner',
      source: 'partner-master',
      fields: [
        { attribute: 'id', field: 'partnerId' },
        { attribute: 'name', field: 'contractPartner' },
      ],
    },
  ],
}

const fromPartnerMaster = (value: string): FieldValue => ({
  value: { type: 'Text', value },
  origin: 'System',
  source: 'partner-master',
})

function draftDocument(values: Record<string, FieldValue>, status = 'Draft'): DocumentView {
  return {
    id: documentId,
    classId: fields.documentClassId,
    classVersion: 1,
    classPath: ['contract'],
    title: 'Service contract ACME',
    authorId: 'author-1',
    templateId,
    templateVersion: 1,
    createdAt: '2026-10-20T10:00:00Z',
    legalHold: false,
    canAssignNumber: false,
    canStartVersion: false,
    canDiscardDraft: false,
    kind: 'Structured',
    current: {
      id: 'v1',
      version: status === 'Draft' ? '1.0-draft.2' : '1.0-draft.3',
      workingState: 2,
      status,
      changeKind: 'Major',
      requiresAcknowledgement: true,
      scope: '',
      classVersion: 1,
      templateId,
      templateVersion: 1,
      content: {},
      values: {
        title: { value: { type: 'Text', value: 'Service contract ACME' }, origin: 'Manual' },
        partnerId: fromPartnerMaster('P-10001'),
        contractPartner: fromPartnerMaster('Nordlicht Logistik GmbH'),
        documentId: { value: { type: 'Text', value: documentId }, origin: 'System', source: 'dms' },
        ...values,
      },
      createdBy: 'author-1',
      createdAt: '2026-10-20T10:00:00Z',
      updatedAt: '2026-10-21T10:00:00Z',
      requiresExtraReview: false,
    },
  }
}

const list: DocumentSummary[] = [
  {
    id: documentId,
    title: 'Service contract ACME',
    classPath: ['contract'],
    templateId,
    templateVersion: 1,
    authorId: 'author-1',
    versionId: 'v1',
    version: '1.0-draft.2',
    status: 'Draft',
    updatedAt: '2026-10-21T10:00:00Z',
    mine: true,
    kind: 'Structured',
    legalHold: false,
  },
  {
    id: reviewedId,
    title: 'Travel policy',
    classPath: ['policy'],
    templateId,
    templateVersion: 1,
    authorId: 'author-2',
    versionId: 'v2',
    version: '2.0-draft.1',
    status: 'InReview',
    updatedAt: '2026-10-19T10:00:00Z',
    mine: false,
    documentNumber: 'RL-2026-0042',
    kind: 'Structured',
    legalHold: true,
  },
]

const runningCase: WorkflowCase = {
  id: 'c1',
  documentId,
  versionId: 'v1',
  classKey: 'contract',
  fourEyes: true,
  round: 1,
  status: 'Running',
  currentStep: 'legal',
  contentSha256: 'abc',
  tasks: [
    {
      id: 't1',
      caseId: 'c1',
      documentId,
      versionId: 'v1',
      round: 1,
      step: 'legal',
      stepName: 'Legal review',
      kind: 'Review',
      lane: 'legal',
      role: 'Reviewer',
      candidates: ['reviewer-1'],
      status: 'Open',
      createdAt: '2026-10-22T10:00:00Z',
    },
  ],
  votes: [],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface State {
  document: DocumentView
  cases: WorkflowCase[]
  saveReply: () => Reply
  submitReplies: (() => Reply)[]
  missing?: boolean
  ack: { opened: boolean; acknowledgedAt?: string; openedReply?: () => Reply }
}

let state: State

beforeEach(() => {
  state = {
    document: draftDocument({
      termMonths: { value: { type: 'Number', value: 12 }, origin: 'Manual' },
      totalValue: { value: { type: 'Number', value: 1200 }, origin: 'Computed' },
    }),
    cases: [],
    saveReply: () => ok({ ...state.document.current, version: '1.0-draft.3' }),
    submitReplies: [],
    ack: { opened: false },
  }
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/documents':
        return ok(list)
      case '/api/v1/documents/{id}':
        return state.missing
          ? failed(404, { code: 'not_found', message: 'document does not exist' })
          : ok(state.document)
      case '/api/v1/documents/{id}/workflow':
        return ok(state.cases)
      case '/api/v1/documents/{id}/versions':
        return ok([state.document.current])
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/documents/{id}/versions/{versionId}/acknowledgement':
        return ok({
          versionId: state.document.current.id,
          versionLabel: state.document.current.version,
          requested: true,
          opened: state.ack.opened,
          acknowledgedAt: state.ack.acknowledgedAt,
        })
      case '/api/v1/me':
        return ok({ userId: 'author-1', tenantId: 'tenant-1', roles: ['Author'] })
      default:
        return ok(fields)
    }
  })
  const post = vi.fn((p: string) => {
    if (p === '/api/v1/documents/{id}/submit') {
      const reply = state.submitReplies.shift()
      if (reply) return reply()
      state.document = draftDocument({}, 'InReview')
      state.cases = [runningCase]
      return ok(state.document.current)
    }
    if (p === '/api/v1/documents/{id}/versions/{versionId}/opened') {
      if (state.ack.openedReply) return state.ack.openedReply()
      state.ack.opened = true
      return ok(undefined, 204)
    }
    if (p === '/api/v1/documents/{id}/versions/{versionId}/acknowledgement') {
      if (!state.ack.opened) {
        return failed(409, { code: 'not_opened', message: 'version 1.0 has to be opened first' })
      }
      state.ack.acknowledgedAt = '2026-10-27T09:05:00Z'
      return ok(
        {
          documentId,
          versionId: state.document.current.id,
          versionLabel: '1.0',
          openedAt: '2026-10-27T09:00:00Z',
          acknowledgedAt: state.ack.acknowledgedAt,
        },
        201,
      )
    }
    return ok({ html: '<article class="dms-document"></article>', missingRequired: [], issues: [] })
  })
  const put = vi.fn((p: string) =>
    p === '/api/v1/documents/{id}/draft' ? state.saveReply() : ok(state.document.current),
  )
  const del = vi.fn(() => ok(state.document.current))
  const client = { GET: get, POST: post, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, put, del, user: userEvent.setup() }
}

const term = () => screen.getByLabelText(/^Term in months\*?$/)

/** The message next to "Save draft" and "Submit for review" (field messages are alerts too). */
function statusRegion(): HTMLElement {
  const region = document.getElementById('save-draft-note')
  if (!region) throw new Error('no save status')
  return region
}
const statusAlert = () => within(statusRegion()).getByRole('alert')
const findStatusAlert = async () => {
  await screen.findByRole('button', { name: 'Save draft' })
  return within(statusRegion()).findByRole('alert')
}

describe('Document list', () => {
  it('shows the own drafts apart from other documents and opens a draft with its saved values', async () => {
    const { router, user } = renderAt('/documents')

    const drafts = await screen.findByRole('table', { name: 'My drafts' })
    const others = screen.getByRole('table', { name: 'Other documents' })
    expect(within(drafts).getByRole('link', { name: 'Service contract ACME' })).toBeInTheDocument()
    expect(within(drafts).getByText('Draft')).toBeInTheDocument()
    expect(within(others).getByRole('link', { name: 'Travel policy' })).toBeInTheDocument()
    expect(within(others).getByText('In review')).toBeInTheDocument()
    // The document number (DMS-113) has its own column; documents without one leave it empty.
    expect(within(others).getByRole('columnheader', { name: 'No.' })).toBeInTheDocument()
    expect(within(others).getByText('RL-2026-0042')).toBeInTheDocument()
    // Documents under legal hold are marked (DMS-106), the others are not.
    expect(within(others).getByTestId('legal-hold-badge')).toHaveTextContent('Legal Hold')
    expect(within(drafts).queryByTestId('legal-hold-badge')).not.toBeInTheDocument()
    expect(within(drafts).queryByText(/RL-/)).not.toBeInTheDocument()

    await user.click(within(drafts).getByRole('link', { name: 'Service contract ACME' }))

    expect(router.state.location.pathname).toBe(`/documents/${documentId}`)
    expect(await screen.findByLabelText(/^Title\*?$/)).toHaveValue('Service contract ACME')
    expect(term()).toHaveValue('12')
    // The partner taken over from the partner master is shown as picked, its field read-only.
    expect(screen.getByText('(P-10001)')).toBeInTheDocument()
    expect(
      screen.getByLabelText(/^Contract partner\*?$/, { selector: 'input[readonly]' }),
    ).toHaveValue('Nordlicht Logistik GmbH')
  })
})

describe('Document page with a draft', () => {
  it('saves the entered values as the next working state of the draft', async () => {
    const { put, del, user } = renderAt(`/documents/${documentId}`)
    await screen.findByRole('heading', { name: 'Service contract ACME' })
    await waitFor(() => {
      expect(term()).toHaveValue('12')
    })

    await user.clear(term())
    await user.type(term(), '24')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Draft saved as version 1.0-draft.3.')).toBeInTheDocument()
    expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/draft', {
      params: { path: { id: documentId } },
      body: {
        title: 'Service contract ACME',
        values: {
          title: { type: 'Text', value: 'Service contract ACME' },
          termMonths: { type: 'Number', value: 24 },
        },
      },
    })
    // The partner did not change, so it is not taken over again.
    expect(put).not.toHaveBeenCalledWith(
      '/api/v1/documents/{id}/references/{key}',
      expect.anything(),
    )
    expect(del).not.toHaveBeenCalled()
  })

  it('shows values the backend rejects at their fields', async () => {
    state.saveReply = () =>
      failed(422, {
        code: 'invalid_values',
        message: 'Term in months: must be at most 60.',
        issues: [
          {
            field: 'termMonths',
            label: 'Term in months',
            code: 'out_of_range',
            message: 'Must be at most 60.',
          },
        ],
      })
    const { user } = renderAt(`/documents/${documentId}`)
    await waitFor(() => {
      expect(term()).toHaveValue('12')
    })
    await user.clear(term())
    await user.type(term(), '72')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('Must be at most 60.')).toBeInTheDocument()
    expect(term()).toHaveAttribute('aria-invalid', 'true')
    expect(statusAlert()).toHaveTextContent('Correct the marked fields')

    // The message disappears once the value changes.
    await user.type(term(), '0')
    expect(screen.queryByText('Must be at most 60.')).not.toBeInTheDocument()
  })

  it('lists the values missing for review, then submits and shows the workflow state', async () => {
    state.document = draftDocument({})
    state.submitReplies = [
      () =>
        failed(422, {
          code: 'incomplete',
          message: 'Required for review: Term in months',
          issues: [
            {
              field: 'termMonths',
              label: 'Term in months',
              code: 'missing',
              message: 'Required for review.',
            },
          ],
        }),
    ]
    const { post, put, user } = renderAt(`/documents/${documentId}`)
    expect(await screen.findByText('Not submitted yet.')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByLabelText(/^Title\*?$/)).toHaveValue('Service contract ACME')
    })

    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    const alert = await findStatusAlert()
    expect(alert).toHaveTextContent('cannot be submitted yet')
    expect(screen.getByText('Required for review.')).toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Term in months' }))
    expect(term()).toHaveFocus()
    // Submitting saves the draft first.
    expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/draft', expect.anything())

    await user.type(term(), '24')
    await user.click(screen.getByRole('button', { name: 'Submit for review' }))

    const workflow = await screen.findByTestId('workflow-case')
    expect(within(workflow).getByText('Running')).toBeInTheDocument()
    expect(within(workflow).getByText(/current step: Legal review/)).toBeInTheDocument()
    expect(within(workflow).getByText('reviewer-1')).toBeInTheDocument()
    expect(screen.getAllByText('In review').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/submit', {
      params: { path: { id: documentId } },
    })
  })

  it('explains conflicts and unauthorized deviations at submission', async () => {
    state.submitReplies = [
      () => failed(409, { code: 'lifecycle', message: 'The version is already in review.' }),
      () =>
        failed(422, {
          code: 'invalid',
          message: "Locked section 'parties' was changed.",
        }),
    ]
    const { user } = renderAt(`/documents/${documentId}`)
    await waitFor(() => {
      expect(term()).toHaveValue('12')
    })

    await user.click(screen.getByRole('button', { name: 'Submit for review' }))
    expect(await findStatusAlert()).toHaveTextContent(
      'The draft could not be submitted: The version is already in review.',
    )
    expect(statusAlert()).toHaveTextContent('reload the page')

    await user.click(screen.getByRole('button', { name: 'Submit for review' }))
    await waitFor(() => {
      expect(statusAlert()).toHaveTextContent(
        'Changes to locked sections need an authorized deviation request.',
      )
    })
  })

  it('tells the reader when the document does not exist', async () => {
    state.missing = true
    renderAt(`/documents/${documentId}`)
    expect(await screen.findByRole('alert')).toHaveTextContent('This document does not exist.')
  })
})

describe('Document page with a published version', () => {
  function published(requiresAcknowledgement = true): DocumentView {
    const d = draftDocument({}, 'Published')
    return {
      ...d,
      current: { ...d.current, version: '1.0', requiresAcknowledgement },
    }
  }

  it('records the opening and lets the reader acknowledge the version', async () => {
    state.document = published()
    const { post, user } = renderAt(`/documents/${documentId}`)

    const button = await screen.findByRole('button', { name: 'Acknowledge' })
    await waitFor(() => {
      expect(button).toBeEnabled()
    })
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/opened', {
      params: { path: { id: documentId, versionId: 'v1' } },
    })
    await user.click(button)

    expect(await screen.findByTestId('acknowledged')).toHaveTextContent(
      'You acknowledged version 1.0 on',
    )
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/acknowledgement',
      { params: { path: { id: documentId, versionId: 'v1' } } },
    )
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
  })

  it('keeps acknowledging disabled while the opening could not be recorded', async () => {
    state.document = published()
    state.ack.openedReply = () =>
      failed(503, { code: 'database_error', message: 'The opening could not be recorded.' })
    renderAt(`/documents/${documentId}`)

    expect(await screen.findByText('The opening could not be recorded.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Acknowledge' })).toBeDisabled()
  })

  it('shows an earlier acknowledgement', async () => {
    state.document = published()
    state.ack = { opened: true, acknowledgedAt: '2026-10-20T08:00:00Z' }
    renderAt(`/documents/${documentId}`)

    expect(await screen.findByTestId('acknowledged')).toHaveTextContent('version 1.0')
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
  })

  it('does not ask for an acknowledgement when the version does not require it', async () => {
    state.document = published(false)
    const { post } = renderAt(`/documents/${documentId}`)

    await screen.findByRole('heading', { name: 'Service contract ACME' })
    expect(screen.queryByText('Acknowledgement')).not.toBeInTheDocument()
    expect(post).not.toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/opened',
      expect.anything(),
    )
  })
})
