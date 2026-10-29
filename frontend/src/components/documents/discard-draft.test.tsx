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

/** Discarding a draft on the document page (DMS-129). */

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
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d9'

const fields: TemplateFields = {
  templateId,
  version: 1,
  name: 'Travel policy',
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

function version(id: string, label: string, status: string): DocumentVersionView {
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
    values: { title: { value: { type: 'Text', value: 'Travel policy' }, origin: 'Manual' } },
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
  }
}

function documentWith(
  current: DocumentVersionView,
  extra: Partial<DocumentView> = {},
): DocumentView {
  return {
    id: documentId,
    classId: fields.documentClassId,
    classVersion: 1,
    classPath: ['policy'],
    title: 'Travel policy',
    authorId: 'author-1',
    templateId,
    templateVersion: 1,
    createdAt: '2026-10-20T10:00:00Z',
    legalHold: false,
    canAssignNumber: false,
    canStartVersion: false,
    canDiscardDraft: true,
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

const firstDraft = version('v1', '1.0-draft.2', 'Draft')
const published = version('v1', '1.0', 'Published')
const newDraft = version('v2', '2.0-draft.1', 'Draft')

let state: { document: DocumentView; discardReply?: () => Reply }

beforeEach(() => {
  state = { document: documentWith(firstDraft) }
})

afterEach(() => {
  setLocale('en')
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/documents/{id}':
        return ok(state.document)
      case '/api/v1/documents':
      case '/api/v1/documents/{id}/versions':
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
  const post = vi.fn((p: string) => {
    if (p === '/api/v1/documents/{id}/draft/discard') {
      if (state.discardReply) return state.discardReply()
      const first = state.document.current.version.startsWith('1.0-')
      const reply = {
        documentId,
        versionId: state.document.current.id,
        version: state.document.current.version,
        documentDiscarded: first,
        validVersionId: first ? undefined : published.id,
        validVersion: first ? undefined : published.version,
        removedAttachments: 0,
        releasedFiles: 0,
        keptFiles: 0,
        cancelledTasks: 0,
      }
      if (!first) state.document = documentWith(published, { canDiscardDraft: false })
      return ok(reply)
    }
    return ok({ html: '<article class="dms-document"></article>', missingRequired: [], issues: [] })
  })
  const put = vi.fn(() => ok(state.document.current))
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
  return { router, post, user: userEvent.setup() }
}

const page = `/documents/${documentId}`

describe('Discard draft on the document page (DMS-129)', () => {
  it('is hidden for callers who may not discard and for versions that are not drafts', async () => {
    state.document = documentWith(firstDraft, { canDiscardDraft: false })
    renderAt(page)
    await screen.findByRole('heading', { name: 'Travel policy' })
    expect(screen.queryByRole('button', { name: /Discard draft/ })).not.toBeInTheDocument()
  })

  it('discards a first draft with a reason after confirmation and opens the document list', async () => {
    const { router, post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /Discard draft/ }))
    const dialog = screen.getByRole('dialog', { name: 'Discard draft?' })
    expect(dialog).toHaveAccessibleDescription(/first version of this document/)
    expect(within(dialog).getByText(/files of other versions are not affected/)).toBeInTheDocument()

    // Cancelling discards nothing.
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(post).not.toHaveBeenCalledWith('/api/v1/documents/{id}/draft/discard', expect.anything())

    await user.click(screen.getByRole('button', { name: /Discard draft/ }))
    const again = screen.getByRole('dialog', { name: 'Discard draft?' })
    await user.type(within(again).getByLabelText('Reason (optional)'), '  Created by accident ')
    await user.click(within(again).getByRole('button', { name: 'Discard draft' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/draft/discard', {
        params: { path: { id: documentId } },
        body: { reason: 'Created by accident' },
      })
    })
    expect(
      await screen.findByText('Draft 1.0-draft.2 discarded; the document was removed.'),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/documents')
    })
  })

  it('discards only the new draft without a reason and shows the valid version again', async () => {
    state.document = documentWith(newDraft)
    const { router, post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /Discard draft/ }))
    const dialog = screen.getByRole('dialog', { name: 'Discard draft?' })
    expect(dialog).toHaveAccessibleDescription(/the valid version stays unchanged/)
    await user.click(within(dialog).getByRole('button', { name: 'Discard draft' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/draft/discard', {
        params: { path: { id: documentId } },
        body: {},
      })
    })
    expect(
      await screen.findByText('Draft 2.0-draft.1 discarded; version 1.0 stays valid.'),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(page)
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /Discard draft/ })).not.toBeInTheDocument()
    })
  })

  it('explains a conflict and limits the reason', async () => {
    state.discardReply = () =>
      failed(409, {
        code: 'conflict',
        message:
          'version 1.0-draft.2 is InReview: only drafts that are not submitted can be discarded',
      })
    const { post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /Discard draft/ }))
    const dialog = screen.getByRole('dialog', { name: 'Discard draft?' })
    const reason = within(dialog).getByLabelText('Reason (optional)')
    await user.click(reason)
    await user.paste('x'.repeat(4001))
    await user.click(within(dialog).getByRole('button', { name: 'Discard draft' }))
    expect(
      within(dialog).getByText(/At most 4,?000 characters \(4,?001 entered\)/),
    ).toHaveAttribute('role', 'alert')
    expect(reason).toHaveAttribute('aria-invalid', 'true')
    expect(post).not.toHaveBeenCalledWith('/api/v1/documents/{id}/draft/discard', expect.anything())

    await user.clear(reason)
    await user.click(within(dialog).getByRole('button', { name: 'Discard draft' }))
    expect(
      await within(dialog).findByText(/not discarded: version 1\.0-draft\.2 is InReview/),
    ).toHaveAttribute('role', 'alert')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: /Entwurf verwerfen/ }))
    const dialog = screen.getByRole('dialog', { name: 'Entwurf verwerfen?' })
    expect(dialog).toHaveAccessibleDescription(/erste Version dieses Dokuments/)
    expect(within(dialog).getByLabelText('Begründung (optional)')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Abbrechen' })).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Entwurf verwerfen' }))
    expect(
      await screen.findByText('Entwurf 1.0-draft.2 verworfen; das Dokument wurde entfernt.'),
    ).toBeInTheDocument()
  })
})
