import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { ApiClientProvider } from '@/api/context'
import type { TemplateFields } from '@/api/templates'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

/** Starting a new version and editing its change summary on the document page (DMS-103). */

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

const fields: TemplateFields = {
  templateId,
  version: 1,
  name: 'Code of conduct',
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
  ],
}

function version(
  id: string,
  label: string,
  status: string,
  extra: Partial<DocumentVersionView> = {},
): DocumentVersionView {
  return {
    id,
    version: label,
    workingState: 1,
    status,
    changeKind: 'Major',
    requiresAcknowledgement: true,
    scope: '',
    classVersion: 1,
    templateId,
    templateVersion: 1,
    content: {},
    values: { title: { value: { type: 'Text', value: 'Code of conduct' }, origin: 'Manual' } },
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
    ...extra,
  }
}

const published = version('v1', '1.1', 'Published', {
  releasedBy: 'approver-1',
  releasedAt: '2026-10-25T09:00:00Z',
})

function documentWith(
  current: DocumentVersionView,
  extra: Partial<DocumentView> = {},
): DocumentView {
  return {
    id: documentId,
    classId: fields.documentClassId,
    classVersion: 1,
    classPath: ['policy'],
    title: 'Code of conduct',
    authorId: 'author-1',
    templateId,
    templateVersion: 1,
    createdAt: '2026-10-20T10:00:00Z',
    legalHold: false,
    canAssignNumber: false,
    canStartVersion: true,
    kind: 'Structured',
    current,
    ...extra,
  }
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface State {
  document: DocumentView
  versions: DocumentVersionView[]
  summaryReply?: () => Reply
}

let state: State

beforeEach(() => {
  state = { document: documentWith(published), versions: [published] }
})

afterEach(() => {
  setLocale('en')
})

interface Init {
  body?: { changeKind?: string; reason?: string }
}

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/documents/{id}':
        return ok(state.document)
      case '/api/v1/documents/{id}/versions':
        return ok(state.versions)
      case '/api/v1/documents/{id}/workflow':
      case '/api/v1/workflow/tasks':
      case '/api/v1/users':
        return ok([])
      case '/api/v1/documents/{id}/versions/{versionId}/html':
        return ok({
          versionId: state.document.current.id,
          version: state.document.current.version,
          status: state.document.current.status,
          language: 'en',
          html: '<article class="dms-document"><p>Wording</p></article>',
        })
      case '/api/v1/me':
        return ok({ userId: 'author-1', tenantId: 'tenant-1', roles: ['Author'] })
      default:
        return ok(fields)
    }
  })
  const post = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/documents/{id}/versions') {
      const kind = init?.body?.changeKind ?? 'Major'
      const draft = version('v2', kind === 'Minor' ? '1.2-draft.1' : '2.0-draft.1', 'Draft', {
        changeKind: kind,
        changeReason: init?.body?.reason,
      })
      state.document = documentWith(draft, {
        canStartVersion: false,
        startVersionBlocked: 'OpenVersion',
      })
      state.versions = [published, draft]
      return ok(draft, 201)
    }
    return ok({ html: '<article class="dms-document"></article>', missingRequired: [], issues: [] })
  })
  const put = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/documents/{id}/draft/change-summary') {
      if (state.summaryReply) return state.summaryReply()
      const updated = { ...state.document.current, changeReason: init?.body?.reason }
      state.document = { ...state.document, current: updated }
      state.versions = [published, updated]
      return ok(updated)
    }
    return ok(state.document.current)
  })
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
  return { router, post, put, user: userEvent.setup() }
}

const page = `/documents/${documentId}`

describe('New version on the document page (DMS-103)', () => {
  it('is hidden for callers who may not start versions', async () => {
    state.document = documentWith(published, { canStartVersion: false })
    renderAt(page)
    await screen.findByTestId('version-metadata')
    expect(screen.queryByRole('button', { name: /New version/ })).not.toBeInTheDocument()
  })

  it('is disabled with the reason while an approved version waits for its valid-from date', async () => {
    const scheduled = version('v2', '2.0', 'Approved')
    state.document = documentWith(scheduled, {
      canStartVersion: false,
      startVersionBlocked: 'ScheduledVersion',
    })
    renderAt(page)
    const button = await screen.findByRole('button', { name: /New version/ })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription(/waits for its valid-from date/)
  })

  it('starts an editorial version with its required change summary and opens the new draft', async () => {
    const { router, post, user } = renderAt(`${page}?version=v1`)
    await user.click(await screen.findByRole('button', { name: /New version/ }))
    const dialog = screen.getByRole('dialog', { name: 'Start a new version' })
    expect(within(dialog).getByText(/valid version 1\.1/)).toBeInTheDocument()
    expect(within(dialog).getByText('(becomes version 2.0)')).toBeInTheDocument()
    expect(within(dialog).getByText('(becomes version 1.2)')).toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: /Change of content/ })).toBeChecked()
    expect(within(dialog).getByLabelText('Change summary (recommended)')).not.toBeRequired()

    await user.click(within(dialog).getByRole('radio', { name: /Editorial correction/ }))
    const summary = within(dialog).getByLabelText('Change summary *')
    expect(summary).toHaveAttribute('aria-required', 'true')
    await user.click(within(dialog).getByRole('button', { name: 'Start version' }))
    expect(within(dialog).getByText(/needs a change summary/)).toHaveAttribute('role', 'alert')
    expect(summary).toHaveAttribute('aria-invalid', 'true')
    expect(post).not.toHaveBeenCalledWith('/api/v1/documents/{id}/versions', expect.anything())

    await user.type(summary, '  Typo in rule 5 corrected ')
    await user.click(within(dialog).getByRole('button', { name: 'Start version' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/versions', {
        params: { path: { id: documentId } },
        body: { changeKind: 'Minor', reason: 'Typo in rule 5 corrected' },
      })
    })
    expect(await screen.findByText('Version 1.2-draft.1 started as a draft.')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(router.state.location.search).toEqual({})
    const card = await screen.findByTestId('change-summary')
    expect(within(card).getByText('Typo in rule 5 corrected')).toBeInTheDocument()
    expect(within(card).getByText('Kind of change: Editorial')).toBeInTheDocument()
    // The caller works on the new draft: no disabled "New version" next to it.
    expect(screen.queryByRole('button', { name: /New version/ })).not.toBeInTheDocument()
  })

  it('sends no summary for a major version without one and explains a conflict', async () => {
    const { post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /New version/ }))
    post.mockImplementationOnce(() =>
      failed(409, {
        code: 'conflict',
        message: 'version 2.0-draft.1 is still Draft; finish it first',
      }),
    )
    await user.click(screen.getByRole('button', { name: 'Start version' }))
    expect(
      await screen.findByText(/not started: version 2\.0-draft\.1 is still Draft/),
    ).toHaveAttribute('role', 'alert')
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/versions', {
      params: { path: { id: documentId } },
      body: { changeKind: 'Major' },
    })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('tells authors of a file document to upload the revised file', async () => {
    state.document = documentWith(published, { kind: 'File' })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /New version/ }))
    expect(screen.getByRole('dialog')).toHaveAccessibleDescription(/upload the revised file/)
  })
})

describe('Change summary of a draft (DMS-103)', () => {
  const draft = (extra: Partial<DocumentVersionView> = {}) =>
    version('v2', '2.0-draft.1', 'Draft', { changeReason: 'Rule 7 rewritten', ...extra })

  it('is edited until submission and shown in the version history', async () => {
    state.document = documentWith(draft(), {
      canStartVersion: false,
      startVersionBlocked: 'OpenVersion',
    })
    state.versions = [published, draft()]
    const { put, user } = renderAt(page)
    const card = await screen.findByTestId('change-summary')
    expect(within(card).getByText('Rule 7 rewritten')).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Edit change summary' }))
    const field = within(card).getByLabelText('Change summary')
    expect(field).toHaveValue('Rule 7 rewritten')
    await user.clear(field)
    await user.type(field, 'Rule 7 rewritten: new reporting channel')
    await user.click(within(card).getByRole('button', { name: 'Save summary' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/documents/{id}/draft/change-summary', {
        params: { path: { id: documentId } },
        body: { reason: 'Rule 7 rewritten: new reporting channel' },
      })
    })
    expect(await screen.findByText('Change summary saved.')).toBeInTheDocument()
    expect(
      within(card).getByText('Rule 7 rewritten: new reporting channel', { selector: 'p' }),
    ).toBeInTheDocument()
    const history = screen.getByRole('list', { name: 'Versions' })
    await waitFor(() => {
      expect(
        within(history).getByText('Rule 7 rewritten: new reporting channel'),
      ).toBeInTheDocument()
    })
  })

  it('keeps the summary of an editorial version and shows a failed save', async () => {
    const minor = draft({ changeKind: 'Minor', changeReason: 'Typo' })
    state.document = documentWith(minor, { canStartVersion: false })
    state.versions = [published, minor]
    state.summaryReply = () =>
      failed(409, { code: 'conflict', message: 'version 1.2-draft.1 is InReview' })
    const { put, user } = renderAt(page)
    const card = await screen.findByTestId('change-summary')
    await user.click(within(card).getByRole('button', { name: 'Edit change summary' }))
    const field = within(card).getByLabelText('Change summary *')
    await user.clear(field)
    await user.click(within(card).getByRole('button', { name: 'Save summary' }))
    expect(within(card).getByText(/editorial correction needs/)).toHaveAttribute('role', 'alert')
    expect(put).not.toHaveBeenCalledWith(
      '/api/v1/documents/{id}/draft/change-summary',
      expect.anything(),
    )

    await user.type(field, 'Typo in rule 5')
    await user.click(within(card).getByRole('button', { name: 'Save summary' }))
    expect(
      await within(card).findByText(/Not saved: version 1\.2-draft\.1 is InReview/),
    ).toHaveAttribute('role', 'alert')
    expect(within(card).getByLabelText('Change summary *')).toBeInTheDocument()
  })

  it('is not offered on a first draft without a summary', async () => {
    state.document = documentWith(version('v1', '1.0-draft.2', 'Draft'), { canStartVersion: false })
    state.versions = [state.document.current]
    renderAt(page)
    await screen.findByTestId('version-metadata')
    expect(screen.queryByTestId('change-summary')).not.toBeInTheDocument()
  })
})

describe('New version in German (DMS-103)', () => {
  it('speaks German in the dialog and the change summary', async () => {
    setLocale('de')
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /Neue Version/ }))
    const dialog = screen.getByRole('dialog', { name: 'Neue Version starten' })
    expect(within(dialog).getByText('(wird Version 2.0)')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Änderungsübersicht (empfohlen)')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('radio', { name: /Redaktionelle Korrektur/ }))
    await user.type(within(dialog).getByLabelText('Änderungsübersicht *'), 'Tippfehler')
    await user.click(within(dialog).getByRole('button', { name: 'Version starten' }))
    expect(
      await screen.findByText('Version 1.2-draft.1 als Entwurf gestartet.'),
    ).toBeInTheDocument()
    const card = await screen.findByTestId('change-summary')
    expect(within(card).getByText('Art der Änderung: Redaktionell')).toBeInTheDocument()
    expect(
      within(card).getByRole('button', { name: 'Änderungsübersicht bearbeiten' }),
    ).toBeInTheDocument()
  })
})
