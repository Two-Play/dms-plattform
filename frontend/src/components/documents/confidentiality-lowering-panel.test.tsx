import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { levelsBelow, type Lowering, type Lowerings } from '@/api/confidentiality-lowering'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { decisionsFor, stepLabel } from '@/api/workflow'
import { setLocale, t } from '@/i18n'
import {
  ConfidentialityLoweringPanel,
  LoweringDecisionNote,
} from './confidentiality-lowering-panel'
import { ProposedValuesNote } from './proposed-values-note'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d9'

function version(status = 'Draft'): DocumentVersionView {
  return {
    id: 'v2',
    version: '2.0-draft.1',
    workingState: 1,
    status,
    changeKind: 'Major',
    requiresAcknowledgement: false,
    scope: '',
    classVersion: 1,
    templateId: '7c1e2f0a-0000-4000-8000-000000000001',
    templateVersion: 1,
    content: { sections: [] },
    values: {},
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
  }
}

const request = (extra: Partial<Lowering> = {}): Lowering => ({
  id: 'l1',
  from: 'Confidential',
  to: 'Internal',
  reason: 'The project is public now',
  requestedBy: 'author-1',
  requestedAt: '2026-11-02T09:00:00Z',
  status: 'Requested',
  ...extra,
})

function state(extra: Partial<Lowerings> = {}): Lowerings {
  return {
    versionId: 'v2',
    version: '2.0-draft.1',
    status: 'Draft',
    level: 'Confidential',
    accessLevel: 'Confidential',
    basisLevel: 'Confidential',
    canRequest: true,
    requests: [],
    ...extra,
  }
}

const ok = (data: unknown, status = 200) =>
  Promise.resolve({ data, response: new Response(null, { status }) })
const failed = (status: number, error: unknown) =>
  Promise.resolve({ error, response: new Response(null, { status }) })

type Reply = () => Promise<{ data?: unknown; error?: unknown; response: Response }>

function setup(load: Reply, post?: Reply) {
  const get = vi.fn((path: string) =>
    path === '/api/v1/documents/{id}/versions/{versionId}/confidentiality-lowering'
      ? load()
      : ok([]),
  )
  const postFn = vi.fn<(path: string, init: { body?: unknown }) => ReturnType<Reply>>(() =>
    post ? post() : ok(state()),
  )
  const client = { GET: get, POST: postFn } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrap = (children: React.ReactNode) => (
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{children}</ApiClientProvider>
    </QueryClientProvider>
  )
  return { get, post: postFn, wrap, user: userEvent.setup() }
}

function renderPanel(
  load: Reply,
  {
    post,
    editable = true,
    status = 'Draft',
  }: {
    post?: Reply
    editable?: boolean
    status?: string
  } = {},
) {
  const context = setup(load, post)
  render(
    context.wrap(
      <ConfidentialityLoweringPanel
        documentId={documentId}
        version={version(status)}
        editable={editable}
      />,
    ),
  )
  return context
}

beforeEach(() => {
  setLocale('en')
})

afterEach(() => {
  setLocale('en')
})

describe('Lowering panel on a draft', () => {
  it('offers the levels below the basis and sends level and reason', async () => {
    const { post, user } = renderPanel(() => ok(state()), {
      post: () => ok(state({ canRequest: false, requests: [request()] }), 201),
    })
    await user.click(await screen.findByRole('button', { name: 'Ask for a lower level' }))
    const level = screen.getByLabelText('New level')
    expect(
      within(level)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Internal', 'Public'])
    await user.selectOptions(level, 'Public')
    await user.type(screen.getByLabelText('Reason (required)'), ' The project is public now ')
    await user.click(screen.getByRole('button', { name: 'Send request' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalled()
    })
    expect(post.mock.calls[0]?.[1].body).toEqual({
      level: 'Public',
      reason: 'The project is public now',
    })
  })

  it('needs a reason before it sends anything', async () => {
    const { post, user } = renderPanel(() => ok(state()))
    await user.click(await screen.findByRole('button', { name: 'Ask for a lower level' }))
    await user.click(screen.getByRole('button', { name: 'Send request' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Please give a reason.')
    expect(post).not.toHaveBeenCalled()
  })

  it('says why a request failed, also that the state was out of date', async () => {
    const { user } = renderPanel(() => ok(state()), {
      post: () => failed(409, { code: 'conflict', message: 'x' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Ask for a lower level' }))
    await user.type(screen.getByLabelText('Reason (required)'), 'Why')
    await user.click(screen.getByRole('button', { name: 'Send request' }))
    expect(await screen.findByText(t('lowering.request.conflict'))).toBeInTheDocument()
  })

  it('shows an open request with who asked, and it can be withdrawn', async () => {
    const { post, user } = renderPanel(() =>
      ok(state({ canRequest: false, requests: [request()] })),
    )
    const item = await screen.findByTestId('lowering-request')
    expect(item).toHaveTextContent('Confidential → Internal')
    expect(item).toHaveTextContent('Open')
    expect(item).toHaveTextContent('The project is public now')
    expect(item).toHaveTextContent(t('lowering.state.draft'))
    await user.click(screen.getByRole('button', { name: 'Withdraw request' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(
        '/api/v1/documents/{id}/confidentiality-lowering/withdraw',
        {
          params: { path: { id: documentId } },
        },
      )
    })
  })

  it('offers asking again after a refusal, with the reason of the decision', async () => {
    renderPanel(() =>
      ok(
        state({
          requests: [
            request({
              status: 'Refused',
              decidedBy: 'approver-1',
              decidedAt: '2026-11-03T09:00:00Z',
              decisionReason: 'Still sensitive',
            }),
          ],
        }),
      ),
    )
    const item = await screen.findByTestId('lowering-request')
    expect(item).toHaveTextContent('Refused')
    expect(item).toHaveTextContent('Still sensitive')
    expect(screen.getByRole('button', { name: 'Ask for a lower level' })).toBeInTheDocument()
  })

  it('offers nothing without the right, or on a first version', async () => {
    const { get } = renderPanel(() =>
      ok(state({ canRequest: false, basisLevel: undefined, requests: [] })),
    )
    await waitFor(() => {
      expect(get).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(screen.queryByTestId('confidentiality-lowering')).not.toBeInTheDocument()
    })
  })

  it('shows a failed load with a way to try again', async () => {
    renderPanel(() => failed(500, { code: 'internal', message: 'x' }))
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('Lowering panel on a submitted version', () => {
  it('tells that the step decides, without actions', async () => {
    renderPanel(
      () =>
        ok(
          state({
            status: 'InReview',
            canRequest: false,
            basisLevel: undefined,
            requests: [request()],
          }),
        ),
      { editable: false, status: 'InReview' },
    )
    expect(await screen.findByTestId('lowering-request')).toHaveTextContent(
      t('lowering.state.inWorkflow'),
    )
    expect(screen.queryByRole('button', { name: 'Withdraw request' })).not.toBeInTheDocument()
  })

  it('shows an approved level that does not apply yet, and who decided for whom', async () => {
    renderPanel(
      () =>
        ok(
          state({
            status: 'Approved',
            level: 'Internal',
            accessLevel: 'Confidential',
            canRequest: false,
            basisLevel: undefined,
            requests: [
              request({
                status: 'Approved',
                decidedBy: 'deputy-1',
                decidedAt: '2026-11-03T09:00:00Z',
                actingFor: 'approver-1',
              }),
            ],
          }),
        ),
      { editable: false, status: 'Approved' },
    )
    const item = await screen.findByTestId('lowering-request')
    expect(item).toHaveTextContent(
      'Approved: Internal applies with the publication of this version. Until then access stays at Confidential.',
    )
    expect(item).toHaveTextContent('on behalf of')
    expect(screen.getByTestId('confidentiality-lowering')).toHaveTextContent(
      'Until it is published, access stays at Confidential',
    )
  })

  it('tells someone without the requests where access stands, and no reason', async () => {
    renderPanel(
      () =>
        ok(
          state({
            status: 'Approved',
            level: 'Internal',
            accessLevel: 'Confidential',
            canRequest: false,
            basisLevel: undefined,
            requests: undefined,
          }),
        ),
      { editable: false, status: 'Approved' },
    )
    expect(await screen.findByTestId('confidentiality-lowering')).toBeInTheDocument()
    expect(screen.queryByTestId('lowering-request')).not.toBeInTheDocument()
  })

  it('shows a lowering in force on the published version', async () => {
    renderPanel(
      () =>
        ok(
          state({
            status: 'Published',
            level: 'Internal',
            accessLevel: 'Internal',
            canRequest: false,
            basisLevel: undefined,
            requests: [request({ status: 'Approved', decidedBy: 'approver-1' })],
          }),
        ),
      { editable: false, status: 'Published' },
    )
    expect(await screen.findByTestId('lowering-request')).toHaveTextContent(
      'In force: this version has been at Internal since its publication.',
    )
  })

  it('is empty on a version without any of this', async () => {
    const { get } = renderPanel(
      () =>
        ok(
          state({
            status: 'Published',
            canRequest: false,
            basisLevel: undefined,
            requests: undefined,
          }),
        ),
      { editable: false, status: 'Published' },
    )
    await waitFor(() => {
      expect(get).toHaveBeenCalled()
    })
    expect(screen.queryByTestId('confidentiality-lowering')).not.toBeInTheDocument()
  })
})

describe('The decision', () => {
  it('shows the approver what they decide on', async () => {
    const { wrap } = setup(() =>
      ok(state({ status: 'InApproval', canRequest: false, requests: [request()] })),
    )
    render(wrap(<LoweringDecisionNote documentId={documentId} versionId="v2" />))
    const note = await screen.findByTestId('lowering-decision')
    expect(note).toHaveTextContent('Confidential → Internal')
    expect(note).toHaveTextContent('The project is public now')
    expect(note).toHaveTextContent(t('lowering.decisionHint'))
  })

  it('knows two answers, and its step has a name in the reader’s language', () => {
    const task = { kind: 'Approval', step: 'confidentiality-lowering', stepName: 'English name' }
    expect(decisionsFor(task)).toEqual(['Approve', 'Reject'])
    expect(decisionsFor({ kind: 'Approval', step: 'approval' })).toContain('ApproveWithConditions')
    setLocale('de')
    expect(stepLabel(task)).toBe('Freigabe der niedrigeren Vertraulichkeit')
    expect(stepLabel({ step: 'legal', stepName: 'Rechtsprüfung' })).toBe('Rechtsprüfung')
  })

  it('gives the levels a draft can ask for, the closest first', () => {
    expect(levelsBelow('StrictlyConfidential')).toEqual(['Confidential', 'Internal', 'Public'])
    expect(levelsBelow('Public')).toEqual([])
  })
})

describe('Proposed title and owner', () => {
  const document = { title: 'Board minutes', ownerId: 'owner-1' } as DocumentView

  function renderNote(values: DocumentVersionView['values'], status = 'Draft') {
    const { wrap } = setup(() => ok(state()))
    render(
      wrap(<ProposedValuesNote document={document} version={{ ...version(status), values }} />),
    )
  }

  const named = {
    title: { value: { type: 'Text', value: 'Renamed' }, origin: { type: 'Manual' } },
    owner: { value: { type: 'PersonRef', userId: 'owner-2' }, origin: { type: 'Manual' } },
  } as unknown as DocumentVersionView['values']

  it('shows what a version on its way proposes', async () => {
    renderNote(named)
    const note = await screen.findByTestId('proposed-values')
    expect(note).toHaveTextContent('New title: Renamed')
    expect(note).toHaveTextContent('New owner:')
  })

  it('says nothing if the version names what stands, or is in force', () => {
    renderNote({
      title: { value: { type: 'Text', value: 'Board minutes' }, origin: { type: 'Manual' } },
      owner: { value: { type: 'PersonRef', userId: 'owner-1' }, origin: { type: 'Manual' } },
    } as unknown as DocumentVersionView['values'])
    expect(screen.queryByTestId('proposed-values')).not.toBeInTheDocument()
  })

  it('says nothing on a published version', () => {
    renderNote(named, 'Published')
    expect(screen.queryByTestId('proposed-values')).not.toBeInTheDocument()
  })
})
