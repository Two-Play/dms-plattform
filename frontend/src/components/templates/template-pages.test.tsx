import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { TemplateImpact, TemplateVersion, TestRun } from '@/api/template-admin'
import type { TemplateFields } from '@/api/templates'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'designer-2', name: 'Dora Designer' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'
const classId = '7c1e2f0a-0000-4000-8000-000000000002'

const content = {
  format: 1,
  sections: [
    {
      id: 'main',
      title: 'Contract',
      locked: false,
      blocks: [{ type: 'Paragraph', content: [{ type: 'Placeholder', path: 'termType' }] }],
    },
  ],
} as unknown as TemplateVersion['content']

function version(n: number, status: string, createdBy = 'designer-1'): TemplateVersion {
  return {
    templateId,
    version: n,
    name: 'Service contract',
    description: 'Standard service contract',
    documentClassId: classId,
    documentClassVersion: 1,
    status,
    content,
    createdBy,
    createdAt: '2026-10-20T10:00:00Z',
    editedBy: [createdBy],
    releasedBy: status === 'Released' ? 'designer-2' : undefined,
    releasedAt: status === 'Released' ? '2026-10-21T10:00:00Z' : undefined,
  }
}

const fields: TemplateFields = {
  templateId,
  version: 2,
  name: 'Service contract',
  documentClassId: classId,
  documentClassVersion: 1,
  fields: [
    {
      key: 'termType',
      label: 'Term',
      fieldType: { type: 'Selection', options: [] },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
  ],
  references: [],
}

const impact: TemplateImpact = {
  templateId,
  version: 2,
  status: 'Testing',
  documents: 3,
  byStatus: [
    { status: 'Draft', versions: 2, documents: 2 },
    { status: 'Published', versions: 1, documents: 1 },
  ],
  byVersion: [{ templateVersion: 1, documents: 3, byStatus: [] }],
  openDrafts: [
    {
      documentId: 'd1',
      versionId: 'v1',
      title: 'Draft of author 1',
      titleMasked: false,
      author: 'author-1',
      version: '1.0-draft.1',
      status: 'Draft',
      templateVersion: 1,
      updatedAt: '2026-10-22T10:00:00Z',
    },
    {
      documentId: 'd2',
      versionId: 'v2',
      title: 'Confidential document',
      titleMasked: true,
      author: 'author-2',
      version: '1.0-draft.2',
      status: 'Draft',
      templateVersion: 1,
      updatedAt: '2026-10-21T10:00:00Z',
    },
  ],
  existingDocumentsUnchanged: true,
  explanation: 'Releasing this version changes no existing document.',
}

const testRun: TestRun = {
  cases: [
    {
      name: 'termType == fixed: true',
      values: {},
      text: 'Fixed term',
      html: '<article class="dms-document"><p class="dms-paragraph">Fixed term rendered</p></article>',
      missingRequired: [],
      branches: [],
    },
    {
      name: 'empty optional fields',
      values: {},
      html: '<article class="dms-document"><p class="dms-paragraph">Empty case rendered</p></article>',
      missingRequired: ['termType'],
      branches: [],
    },
  ],
  coverage: [
    {
      location: {
        sectionId: 'main',
        blockPath: '/blocks/1',
        part: 'condition',
        line: 1,
        column: 1,
      },
      condition: "termType == 'fixed'",
      outcomes: [false, true],
    },
  ],
  allBranchesCovered: true,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface State {
  versions: TemplateVersion[]
  statusReply?: () => Reply
  saveReply?: () => Reply
}

let state: State

beforeEach(() => {
  state = { versions: [version(1, 'Released'), version(2, 'Testing')] }
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'designer-2', tenantId: 'tenant-1', roles: ['TemplateDesigner'] })
      case '/api/v1/admin/templates':
        return ok([
          state.versions.at(-1),
          { ...version(4, 'Draft'), templateId: 't2', name: 'Annex' },
        ])
      case '/api/v1/admin/templates/{id}':
        return ok(state.versions)
      case '/api/v1/admin/templates/{id}/versions/{version}/fields':
        return ok(fields)
      case '/api/v1/templates/{id}/versions/{version}/impact':
        return ok(impact)
      default:
        return failed(404, { code: 'not_found', message: `unexpected GET ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: { body?: { status?: string } }) => {
    switch (p) {
      case '/api/v1/admin/templates/{id}/versions/{version}/status': {
        if (state.statusReply) return state.statusReply()
        const to = init?.body?.status ?? ''
        state.versions = state.versions.map((v) =>
          v.version === 2 ? { ...v, status: to, releasedBy: 'designer-2' } : v,
        )
        return ok(state.versions[1])
      }
      case '/api/v1/admin/templates/check':
        return ok({
          valid: false,
          issues: [
            {
              location: {
                sectionId: 'main',
                blockPath: '/blocks/0',
                part: 'placeholder',
                line: 1,
                column: 1,
              },
              message: "unknown field 'termTyp'",
            },
          ],
        })
      case '/api/v1/admin/templates/{id}/versions/{version}/test':
        return ok(testRun)
      default:
        return failed(404, { code: 'not_found', message: `unexpected POST ${p}` })
    }
  })
  const put = vi.fn(() => (state.saveReply ? state.saveReply() : ok(state.versions.at(-1))))
  const client = { GET: get, POST: post, PUT: put } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, put, user: userEvent.setup() }
}

describe('Template list', () => {
  it('lists every template with its latest version and status and opens one', async () => {
    const { router, user } = renderAt('/templates')

    const table = await screen.findByRole('table', { name: 'Templates' })
    const rows = within(table).getAllByRole('row')
    const row = (i: number): HTMLElement => {
      const r = rows[i]
      if (!r) throw new Error(`no row ${String(i)}`)
      return r
    }
    // Sorted by name: Annex before Service contract.
    expect(within(row(1)).getByRole('link', { name: 'Annex' })).toBeInTheDocument()
    expect(within(row(1)).getByText('Draft')).toBeInTheDocument()
    expect(within(row(2)).getByText('Testing')).toBeInTheDocument()
    expect(within(row(2)).getByText('2')).toBeInTheDocument()

    await user.click(within(row(2)).getByRole('link', { name: 'Service contract' }))
    expect(router.state.location.pathname).toBe(`/templates/${templateId}`)
    expect(await screen.findByRole('heading', { name: 'Version 2' })).toBeInTheDocument()
  })
})

describe('Template page', () => {
  it('shows the versions, the history, the fields and the content of a version', async () => {
    renderAt(`/templates/${templateId}?version=1`)

    const nav = await screen.findByRole('navigation', { name: 'Versions' })
    expect(within(nav).getByRole('link', { name: /Version 2/ })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /Version 1/ })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(screen.getByRole('heading', { name: 'Version 1' })).toBeInTheDocument()
    // History: released by the second designer (user ids until display names exist).
    expect(screen.getByText('designer-2')).toBeInTheDocument()
    const fieldTable = await screen.findByRole('table', { name: 'Fields' })
    expect(within(fieldTable).getByText('termType')).toBeInTheDocument()
    expect(within(fieldTable).getByText('Used in template')).toBeInTheDocument()
    // Released versions are read-only and can be deprecated.
    expect(screen.queryByLabelText('Content (JSON)')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deprecate…' })).toBeInTheDocument()
  })

  it('shows the impact analysis before a release and releases the version', async () => {
    const { post, user } = renderAt(`/templates/${templateId}`)

    await user.click(await screen.findByRole('button', { name: 'Release…' }))
    const dialog = await screen.findByRole('dialog')
    expect(
      await within(dialog).findByText('3 documents use an earlier version'),
    ).toBeInTheDocument()
    const counts = within(dialog).getByRole('table', {
      name: 'Documents on earlier versions by status',
    })
    expect(within(counts).getByText('Published')).toBeInTheDocument()
    const drafts = within(dialog).getByRole('table', { name: 'Open drafts on earlier versions' })
    expect(within(drafts).getByText('Draft of author 1')).toBeInTheDocument()
    expect(within(drafts).getByText('author-1')).toBeInTheDocument()
    expect(within(drafts).getByText('Confidential document')).toBeInTheDocument()
    expect(within(dialog).getByText(/changes no existing document/)).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Release version 2' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/admin/templates/{id}/versions/{version}/status',
      expect.objectContaining({ body: { status: 'Released' } }),
    )
    expect(await screen.findByText('Version 2 is now Released.')).toBeInTheDocument()
  })

  it('explains a release refused by the four-eyes principle', async () => {
    state.statusReply = () =>
      failed(403, {
        code: 'segregation_of_duties',
        message: 'designer-2 created or changed this version',
      })
    const { user } = renderAt(`/templates/${templateId}`)

    await user.click(await screen.findByRole('button', { name: 'Release…' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(await within(dialog).findByRole('button', { name: 'Release version 2' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('Four-eyes principle')
    expect(alert).toHaveTextContent('designer-2 created or changed this version')
  })

  it('tells the creator of a version in testing that someone else has to release it', async () => {
    state.versions = [version(1, 'Released'), version(2, 'Testing', 'designer-2')]
    renderAt(`/templates/${templateId}`)

    expect(
      await screen.findByText(/another template designer has to release it/),
    ).toBeInTheDocument()
  })

  it('shows a conflict of a status change with a hint to reload', async () => {
    state.statusReply = () =>
      failed(409, { code: 'conflict', message: 'version 2 was changed concurrently.' })
    const { user } = renderAt(`/templates/${templateId}`)

    await user.click(await screen.findByRole('button', { name: 'Back to draft' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'version 2 was changed concurrently. Reload the page',
    )
  })

  it('shows compiler issues with their location when a draft cannot be saved', async () => {
    state.versions = [version(1, 'Released'), version(2, 'Draft')]
    state.saveReply = () =>
      failed(422, { code: 'invalid_content', message: 'section main: unknown field' })
    const { put, post, user } = renderAt(`/templates/${templateId}`)

    // The JSON is only an advanced view; it is hidden until asked for.
    await user.click(await screen.findByRole('button', { name: 'Advanced: show JSON' }))
    const editor = screen.getByLabelText('Content (JSON)')
    fireEvent.change(editor, { target: { value: '{"sections": [' } })
    await user.click(screen.getByRole('button', { name: 'Apply JSON' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Not valid JSON')
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled()

    fireEvent.change(editor, {
      target: { value: JSON.stringify({ format: 1, sections: [{ id: 'main', locked: false }] }) },
    })
    await user.click(screen.getByRole('button', { name: 'Apply JSON' }))
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    const alert = await screen.findByText('1 issue in the content')
    const summary = alert.closest('[role="alert"]')
    if (!(summary instanceof HTMLElement)) throw new Error('no issue summary')
    await waitFor(() => {
      expect(summary).toHaveTextContent("unknown field 'termTyp'")
    })
    expect(summary).toHaveTextContent('Section main › /blocks/0 › placeholder, line 1, column 1')
    expect(put).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('/api/v1/admin/templates/check', expect.anything())
  })

  it('runs the test mode and shows each case rendered', async () => {
    const { user } = renderAt(`/templates/${templateId}`)

    await user.click(await screen.findByRole('button', { name: 'Run test mode' }))

    expect(
      await screen.findByText('All condition branches covered by 2 test cases.'),
    ).toBeInTheDocument()
    expect(screen.getByText('false and true')).toBeInTheDocument()
    expect(screen.getByTestId('test-case-preview')).toHaveTextContent('Fixed term rendered')
    await user.click(screen.getByRole('button', { name: /empty optional fields/ }))
    expect(screen.getByTestId('test-case-preview')).toHaveTextContent('Empty case rendered')
    expect(screen.getByText('Missing required values: termType')).toBeInTheDocument()
  })
})
