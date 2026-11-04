import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import { ApiClientProvider } from '@/api/context'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

/** Withdrawing an approved version before its valid-from date on the document page (DMS-143). */

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'owner-1', name: 'Olga Owner' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const templateId = '7c1e2f0a-0000-4000-8000-000000000001'
const documentId = '7c1e2f0a-0000-4000-8000-0000000000d9'

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
    values: { title: { value: { type: 'Text', value: 'Travel policy' }, origin: 'Manual' } },
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
    ...extra,
  }
}

const valid = version('v1', '1.0', 'Published')
const scheduled = version('v2', '2.0', 'Approved', { validFrom: '2027-01-01' })
const withdrawn = version('v2', '2.0', 'Withdrawn', {
  validFrom: '2027-01-01',
  withdrawal: {
    at: '2026-11-04T10:00:00Z',
    by: 'owner-1',
    reason: 'Approved by mistake',
  },
})

function documentWith(
  current: DocumentVersionView,
  extra: Partial<DocumentView> = {},
): DocumentView {
  return {
    id: documentId,
    classId: '7c1e2f0a-0000-4000-8000-000000000002',
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
    canDiscardDraft: false,
    kind: 'Structured',
    current,
    ...extra,
  }
}

const withdrawable = {
  versionId: 'v2',
  version: '2.0',
  validFrom: '2027-01-01',
  validVersion: '1.0',
  publicationFailed: false,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

let state: {
  document: DocumentView
  versions: DocumentVersionView[]
  withdrawReply?: () => Reply
}

beforeEach(() => {
  state = {
    document: documentWith(scheduled, { withdrawable }),
    versions: [valid, scheduled],
  }
})

afterEach(() => {
  setLocale('en')
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/documents/{id}':
        return ok(state.document)
      case '/api/v1/documents/{id}/versions':
        return ok(state.versions)
      case '/api/v1/documents':
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
        return ok({ userId: 'owner-1', tenantId: 'tenant-1', roles: ['Owner'] })
      default:
        return ok({})
    }
  })
  const post = vi.fn((p: string) => {
    if (p === '/api/v1/documents/{id}/versions/{versionId}/withdraw') {
      if (state.withdrawReply) return state.withdrawReply()
      state.document = documentWith(valid)
      state.versions = [valid, withdrawn]
      return ok(withdrawn)
    }
    return ok({})
  })
  const client = { GET: get, POST: post, PUT: vi.fn() } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { post, user: userEvent.setup() }
}

const page = `/documents/${documentId}`

describe('Withdraw an approved version on the document page (DMS-143)', () => {
  it('is hidden unless the backend names a version the caller may withdraw', async () => {
    state.document = documentWith(scheduled)
    renderAt(page)
    await screen.findByRole('heading', { name: 'Travel policy' })
    expect(screen.queryByRole('button', { name: /Withdraw version/ })).not.toBeInTheDocument()
  })

  it('asks for a reason in an accessible dialog and sends nothing without one', async () => {
    const { post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Withdraw version 2.0' }))
    const dialog = screen.getByRole('dialog', { name: 'Withdraw version 2.0?' })
    expect(dialog).toHaveAccessibleDescription(/version 1\.0 stays valid/)
    const reason = within(dialog).getByLabelText('Reason')
    expect(reason).toHaveAttribute('aria-required', 'true')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Please give a reason.')
    expect(reason).toHaveAttribute('aria-invalid', 'true')
    expect(reason).toHaveAccessibleDescription('Please give a reason.')
    expect(post).not.toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/withdraw',
      expect.anything(),
    )
  })

  it('withdraws with the reason, then shows the valid version and the mark in the history', async () => {
    const { post, user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Withdraw version 2.0' }))
    const dialog = screen.getByRole('dialog', { name: 'Withdraw version 2.0?' })
    await user.type(within(dialog).getByLabelText('Reason'), '  Approved by mistake ')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/withdraw', {
      params: { path: { id: documentId, versionId: 'v2' } },
      body: { reason: 'Approved by mistake' },
    })
    expect(screen.queryByRole('button', { name: /Withdraw version/ })).not.toBeInTheDocument()
    const history = await screen.findByRole('list', { name: 'Versions' })
    expect(await within(history).findByText('Withdrawn, never valid')).toBeInTheDocument()
    expect(within(history).getByText(/Reason: Approved by mistake/)).toBeInTheDocument()
  })

  it('says who won when the publication got there first', async () => {
    state.withdrawReply = () =>
      failed(409, { code: 'version_published', message: 'version 2.0 was published first' })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Withdraw version 2.0' }))
    const dialog = screen.getByRole('dialog', { name: 'Withdraw version 2.0?' })
    await user.type(within(dialog).getByLabelText('Reason'), 'Approved by mistake')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))
    expect(
      await within(dialog).findByText(/it was published first and is valid now/),
    ).toHaveAttribute('role', 'alert')
  })

  it('tells a first version leaves the document without a valid version, in German as well', async () => {
    setLocale('de')
    state.document = documentWith(scheduled, {
      withdrawable: { ...withdrawable, validVersion: undefined },
    })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Version 2.0 zurückziehen' }))
    const dialog = screen.getByRole('dialog', { name: 'Version 2.0 zurückziehen?' })
    expect(dialog).toHaveAccessibleDescription(/keine gültige Version/)
  })

  it('says the publication failed, not that the version would become valid, once its date has passed', async () => {
    state.document = documentWith(scheduled, {
      withdrawable: { ...withdrawable, publicationFailed: true },
    })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Withdraw version 2.0' }))
    const dialog = screen.getByRole('dialog', { name: 'Withdraw version 2.0?' })
    expect(dialog).toHaveAccessibleDescription(/failed for good.*a new version can be started/)
    expect(dialog).not.toHaveAccessibleDescription(/would become valid/)
  })

  it('says so in German too, also without a valid version', async () => {
    setLocale('de')
    state.document = documentWith(scheduled, {
      withdrawable: { ...withdrawable, validVersion: undefined, publicationFailed: true },
    })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Version 2.0 zurückziehen' }))
    const dialog = screen.getByRole('dialog', { name: 'Version 2.0 zurückziehen?' })
    expect(dialog).toHaveAccessibleDescription(/endgültig fehlgeschlagen.*keine gültige Version/)
  })

  it('says who won when a retry of the failed publication got there first', async () => {
    state.document = documentWith(scheduled, {
      withdrawable: { ...withdrawable, publicationFailed: true },
    })
    state.withdrawReply = () =>
      failed(409, { code: 'version_due', message: 'version 2.0 became due' })
    const { user } = renderAt(page)
    await user.click(await screen.findByRole('button', { name: 'Withdraw version 2.0' }))
    const dialog = screen.getByRole('dialog', { name: 'Withdraw version 2.0?' })
    await user.type(within(dialog).getByLabelText('Reason'), 'A required field is empty')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw version' }))
    expect(await within(dialog).findByText(/it is being published/)).toHaveAttribute(
      'role',
      'alert',
    )
  })

  it('marks a withdrawn version on its own page', async () => {
    state.document = documentWith(withdrawn)
    state.versions = [withdrawn]
    renderAt(page)
    const notice = await screen.findByText(/withdrawn before it took effect/)
    expect(notice).toHaveTextContent('Reason: Approved by mistake')
  })
})
