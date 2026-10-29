import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import type { VersionComparison } from '@/api/versions'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'reader-1', name: 'Rita Reader' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'

function version(
  id: string,
  label: string,
  status: string,
  changeKind: string,
  changeReason?: string,
): DocumentVersionView {
  return {
    id,
    version: label,
    workingState: 1,
    status,
    changeKind,
    changeReason,
    requiresAcknowledgement: false,
    scope: '',
    classVersion: 1,
    templateId,
    templateVersion: 1,
    content: {},
    values: {},
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-20T10:00:00Z',
    requiresExtraReview: false,
  }
}

const published = version('v3', '2.0', 'Published', 'Major', 'Liability capped')
const versions = [
  version('v1', '1.0', 'Superseded', 'Major'),
  version('v2', '1.1', 'Superseded', 'Minor', 'Typo in rule 5'),
  published,
]

const document: DocumentView = {
  id: documentId,
  classId: '7c1e2f0a-0000-4000-8000-000000000002',
  classVersion: 1,
  classPath: ['contract'],
  title: 'Service contract ACME',
  authorId: 'author-1',
  templateId,
  templateVersion: 1,
  createdAt: '2026-10-20T10:00:00Z',
  legalHold: false,
  current: published,
}

const comparison: VersionComparison = {
  documentId,
  title: 'Service contract ACME',
  from: {
    versionId: 'v1',
    version: '1.0',
    status: 'Superseded',
    changeKind: 'Major',
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
  },
  to: {
    versionId: 'v3',
    version: '2.0',
    status: 'Published',
    changeKind: 'Major',
    createdBy: 'author-1',
    createdAt: '2026-10-22T10:00:00Z',
  },
  summaries: [
    {
      versionId: 'v2',
      version: '1.1',
      changeKind: 'Minor',
      summary: 'Typo in rule 5',
      createdBy: 'author-1',
      createdAt: '2026-10-21T10:00:00Z',
    },
    {
      versionId: 'v3',
      version: '2.0',
      changeKind: 'Major',
      summary: 'Liability capped',
      createdBy: 'author-1',
      createdAt: '2026-10-22T10:00:00Z',
    },
  ],
  text: {
    changed: true,
    insertedWords: 2,
    deletedWords: 1,
    sections: [
      {
        id: 'intro',
        titleBefore: 'Introduction',
        titleAfter: 'Introduction',
        status: 'Unchanged',
        moved: false,
        lockedBefore: false,
        lockedAfter: false,
        blocks: [
          {
            path: [],
            location: 'block 0',
            kind: 'Paragraph',
            status: 'Unchanged',
            spans: [{ kind: 'Equal', text: 'This contract applies to all services.' }],
            changes: [],
          },
        ],
      },
      {
        id: 'liability',
        titleBefore: 'Liability',
        titleAfter: 'Limitation of liability',
        status: 'Changed',
        moved: false,
        lockedBefore: true,
        lockedAfter: true,
        blocks: [
          {
            path: [],
            location: 'block 0',
            kind: 'Heading',
            level: 3,
            status: 'Changed',
            spans: [{ kind: 'Equal', text: 'Caps' }],
            changes: [{ kind: 'HeadingLevel', from: '2', to: '3' }],
          },
          {
            path: [],
            location: 'block 1',
            kind: 'Paragraph',
            status: 'Changed',
            spans: [
              { kind: 'Equal', text: 'Liability is limited to' },
              { kind: 'Deleted', text: '<b>double</b>' },
              { kind: 'Inserted', text: 'the annual fee' },
            ],
            changes: [],
          },
        ],
      },
    ],
  },
  fields: [
    {
      key: 'contractPartner',
      label: 'Contract partner',
      before: { type: 'Text', value: 'ACME' },
      after: { type: 'Text', value: 'ACME Ltd' },
      originBefore: 'System',
      sourceBefore: 'partner-master',
      originAfter: 'System',
      sourceAfter: 'partner-master',
    },
  ],
  metadata: [
    {
      key: 'status',
      label: 'Status',
      before: { type: 'Selection', code: 'Superseded' },
      after: { type: 'Selection', code: 'Published' },
      originBefore: 'System',
      sourceBefore: 'dms',
      originAfter: 'System',
      sourceAfter: 'dms',
    },
  ],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

let compareReply: () => Reply

/** The first page of a test loads its route lazily, which can take a while on a busy machine. */
const firstLoad = { timeout: 5000 }

beforeEach(() => {
  compareReply = () => ok(comparison)
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/documents/{id}':
        return ok(document)
      case '/api/v1/documents/{id}/versions':
        return ok(versions)
      case '/api/v1/documents/{id}/compare':
        return compareReply()
      case '/api/v1/documents/{id}/workflow':
        return ok([])
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/me':
        return ok({ userId: 'reader-1', tenantId: 'tenant-1', roles: ['Reader'] })
      default:
        return ok({ templateId, version: 1, name: 'Contract', fields: [], references: [] })
    }
  })
  const client = { GET: get } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, user: userEvent.setup() }
}

describe('Version history', () => {
  it('lists the readable versions newest first with their change summaries', async () => {
    renderAt(`/documents/${documentId}`)

    const list = await screen.findByRole('list', { name: 'Versions' }, firstLoad)
    const items = within(list).getAllByRole('listitem')
    const item = (i: number) => {
      const found = items[i]
      if (!found) throw new Error(`no version at ${String(i)}`)
      return found
    }
    expect(items.map((i) => within(i).getByText(/^Version /).textContent)).toEqual([
      'Version 2.0',
      'Version 1.1',
      'Version 1.0',
    ])
    expect(within(item(0)).getByText('Liability capped')).toBeInTheDocument()
    expect(within(item(1)).getByText(/Editorial/)).toBeInTheDocument()
    expect(within(item(0)).getByRole('link', { name: 'Changes in 2.0 since 1.1' })).toHaveAttribute(
      'href',
      `/documents/${documentId}/compare?from=v2&to=v3`,
    )
    // The first version has no predecessor to compare with.
    expect(within(item(2)).queryByRole('link', { name: /^Changes/ })).not.toBeInTheDocument()
    // Every version but the shown (current) one can be opened on the document page (DMS-112).
    expect(within(item(2)).getByRole('link', { name: 'Show version 1.0' })).toHaveAttribute(
      'href',
      `/documents/${documentId}?version=v1`,
    )
  })

  it('compares two picked versions, the older one first', async () => {
    const { router, user } = renderAt(`/documents/${documentId}`)
    const compare = await screen.findByRole(
      'button',
      { name: 'Compare selected versions' },
      firstLoad,
    )
    expect(compare).toBeDisabled()

    await user.click(screen.getByLabelText('Version 2.0'))
    expect(compare).toBeDisabled()
    await user.click(screen.getByLabelText('Version 1.0'))
    await user.click(compare)

    expect(router.state.location.pathname).toBe(`/documents/${documentId}/compare`)
    expect(router.state.location.search).toEqual({ from: 'v1', to: 'v3' })
    expect(await screen.findByRole('heading', { name: 'Compare versions' })).toBeInTheDocument()
  })

  it('keeps at most two picked versions', async () => {
    const { router, user } = renderAt(`/documents/${documentId}`)
    await screen.findByRole('list', { name: 'Versions' }, firstLoad)

    await user.click(screen.getByLabelText('Version 1.0'))
    await user.click(screen.getByLabelText('Version 1.1'))
    await user.click(screen.getByLabelText('Version 2.0'))
    expect(screen.getByLabelText('Version 1.0')).not.toBeChecked()

    await user.click(screen.getByRole('button', { name: 'Compare selected versions' }))
    expect(router.state.location.search).toEqual({ from: 'v2', to: 'v3' })
  })
})

describe('Version comparison', () => {
  it('shows the change summaries above the text, field and metadata changes', async () => {
    const { get, user } = renderAt(`/documents/${documentId}/compare?from=v1&to=v3`)

    const summaries = await screen.findByRole('list', { name: 'Change summaries' }, firstLoad)
    expect(
      within(summaries)
        .getAllByRole('listitem')
        .map((i) => i.textContent),
    ).toEqual(['1.1 (Editorial): Typo in rule 5', '2.0 (Major): Liability capped'])
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/compare', {
      params: { path: { id: documentId }, query: { from: 'v1', to: 'v3' } },
    })
    // The summary comes before the comparison.
    const tabs = screen.getByRole('tablist')
    expect(summaries.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    // Text: only changed sections by default, insertions and deletions marked.
    const section = screen.getByRole('region', { name: 'Limitation of liability' })
    expect(within(section).getByText('Renamed from “Liability”')).toBeInTheDocument()
    expect(within(section).getByText('Heading level 2 → 3')).toBeInTheDocument()
    expect(section.querySelector('ins')?.textContent).toBe('[inserted: the annual fee]')
    // Content is text, never markup.
    expect(section.querySelector('del')?.textContent).toBe('[deleted: <b>double</b>]')
    expect(section.querySelector('b')).toBeNull()
    expect(screen.queryByRole('region', { name: 'Introduction' })).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('Show unchanged text'))
    expect(screen.getByRole('region', { name: 'Introduction' })).toHaveTextContent(
      'This contract applies to all services.',
    )

    await user.click(screen.getByRole('tab', { name: 'Fields (1)' }))
    const fields = screen.getByRole('table', { name: 'Changed field values' })
    expect(within(fields).getByRole('row', { name: /Contract partner/ })).toHaveTextContent(
      'Contract partnerACME(from partner-master)ACME Ltd(from partner-master)',
    )

    await user.click(screen.getByRole('tab', { name: 'Metadata (1)' }))
    const metadata = screen.getByRole('table', { name: 'Changed metadata' })
    expect(within(metadata).getByRole('row', { name: /Status/ })).toHaveTextContent(
      'StatusSupersededPublished',
    )
  })

  it('explains when one of the versions may not be read', async () => {
    compareReply = () =>
      failed(403, { code: 'forbidden', message: 'You are not allowed to do this: draft.' })
    renderAt(`/documents/${documentId}/compare?from=v1&to=v4`)

    expect(await screen.findByRole('alert', {}, firstLoad)).toHaveTextContent(
      'You are not allowed to read one of these versions.',
    )
  })

  it('asks for two versions when the address names fewer', async () => {
    const { get } = renderAt(`/documents/${documentId}/compare?from=v1`)

    expect(await screen.findByRole('alert', {}, firstLoad)).toHaveTextContent('Pick two versions')
    expect(get).not.toHaveBeenCalledWith('/api/v1/documents/{id}/compare', expect.anything())
  })
})
