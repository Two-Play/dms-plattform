import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { FieldValue, WorkflowCase } from '@/api/documents'
import type { DocumentVersionView, DocumentView } from '@/api/partners'
import type { TemplateFields } from '@/api/templates'
import type { VersionHtml } from '@/api/version-view'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

/** The document page shows the content and metadata of every readable version and offers its PDF (DMS-112). */

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

const fields: TemplateFields = {
  templateId,
  version: 3,
  name: 'Policy',
  documentClassId: '7c1e2f0a-0000-4000-8000-000000000002',
  documentClassVersion: 1,
  fields: [
    {
      key: 'policyArea',
      label: 'Policy area',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
    {
      key: 'organizationalUnit',
      label: 'Organizational unit',
      fieldType: { type: 'Text' },
      multiple: false,
      required: true,
      usedInTemplate: true,
    },
  ],
}

const text = (value: string, origin = 'Manual'): FieldValue => ({
  value: { type: 'Text', value },
  origin,
})

function version(
  id: string,
  label: string,
  status: string,
  extra: Partial<DocumentVersionView> = {},
) {
  const approved = !['Draft', 'InReview', 'InApproval'].includes(status)
  const v: DocumentVersionView = {
    id,
    version: label,
    workingState: 3,
    status,
    changeKind: 'Major',
    requiresAcknowledgement: false,
    scope: '',
    classVersion: 1,
    templateId,
    templateVersion: 3,
    content: {},
    values: {
      title: text('IT security policy'),
      documentNumber: text('POL-2026-0007', 'System'),
      policyArea: text('Information security'),
      organizationalUnit: text('IT Governance'),
      owner: { value: { type: 'PersonRef', userId: 'owner-1' }, origin: 'Manual' },
      validFrom: { value: { type: 'Date', value: '2026-11-01' }, origin: 'Manual' },
      validUntil: { value: { type: 'Date', value: '2028-10-31' }, origin: 'Manual' },
      confidentiality: { value: { type: 'Selection', code: 'Internal' }, origin: 'Manual' },
    },
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
    ...(approved
      ? {
          releasedBy: 'approver-1',
          releasedAt: '2026-10-25T09:00:00Z',
          validFrom: '2026-11-01',
          rendition: { objectKey: 'k', sha256: 'ab'.repeat(32) },
        }
      : {}),
    ...extra,
  }
  return v
}

const published = version('v1', '1.0', 'Published', { publishedAt: '2026-11-01T00:00:00Z' })
const inReview = version('v2', '1.1-draft.3', 'InReview')

function documentWith(current: DocumentVersionView): DocumentView {
  return {
    id: documentId,
    classId: fields.documentClassId,
    classVersion: 1,
    classPath: ['policy'],
    title: 'IT security policy',
    ownerId: 'owner-1',
    authorId: 'author-1',
    templateId,
    templateVersion: 3,
    createdAt: '2026-10-20T10:00:00Z',
    legalHold: false,
    current,
  }
}

const approvalCase: WorkflowCase = {
  id: 'c1',
  documentId,
  versionId: 'v1',
  classKey: 'policy',
  fourEyes: true,
  round: 1,
  status: 'Approved',
  contentSha256: 'abc',
  tasks: [],
  votes: [],
  approval: {
    versionId: 'v1',
    version: '1.0',
    contentSha256: 'abc',
    approvedBy: 'deputy-1',
    authenticatedAt: '2026-10-25T08:59:00Z',
    approvedAt: '2026-10-25T09:00:00Z',
    actingFor: 'approver-1',
  },
}

const names: Record<string, string> = {
  'author-1': 'Ada Author',
  'owner-1': 'Otto Owner',
  'approver-1': 'Anna Approver',
  'deputy-1': 'Dan Deputy',
}

const html = (v: DocumentVersionView, draft: boolean): VersionHtml => ({
  versionId: v.id,
  version: v.version,
  status: v.status,
  language: 'en',
  ...(draft ? { draftMarking: 'Draft – not valid' } : {}),
  html: `<article class="dms-document${draft ? ' dms-draft' : ''}" lang="en"><p class="dms-paragraph">Wording of ${v.version}</p></article>`,
})

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200, headers: Record<string, string> = {}): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status, headers }) })

let current: DocumentVersionView

beforeEach(() => {
  current = published
})

afterEach(() => {
  vi.restoreAllMocks()
  setLocale('en')
})

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: { params?: { path?: { versionId?: string } } }) => {
    switch (p) {
      case '/api/v1/documents/{id}':
        return ok(documentWith(current))
      case '/api/v1/documents/{id}/workflow':
        return ok([approvalCase])
      case '/api/v1/documents/{id}/versions':
        return ok(current.id === 'v1' ? [published] : [published, inReview])
      case '/api/v1/documents/{id}/versions/{versionId}/html': {
        const v = init?.params?.path?.versionId === 'v2' ? inReview : published
        return ok(html(v, v === inReview))
      }
      case '/api/v1/documents/{id}/versions/{versionId}/rendition':
      case '/api/v1/documents/{id}/versions/{versionId}/draft.pdf':
      case '/api/v1/documents/{id}/versions/{versionId}/copy.pdf':
        return ok(new Blob(['%PDF']), 200, {
          'Content-Disposition': `attachment; filename="${p.split('/').pop() ?? ''}"`,
        })
      case '/api/v1/users': {
        const query = (init as { params?: { query?: { ids?: string } } } | undefined)?.params?.query
        const ids = (query?.ids ?? '').split(',').filter((id) => id !== '')
        return ok(ids.map((id) => ({ id, displayName: names[id] })))
      }
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/me':
        return ok({ userId: 'reader-1', tenantId: 'tenant-1', roles: ['Reader'] })
      default:
        return ok(fields)
    }
  })
  const client = { GET: get, POST: vi.fn(() => ok(undefined, 204)) } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, router, user: userEvent.setup() }
}

function captureDownloads(): string[] {
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:pdf'), revokeObjectURL: vi.fn() })
  const downloads: string[] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    downloads.push(this.download)
  })
  return downloads
}

const metadata = () => screen.getByTestId('version-metadata')

/** The value next to a label in the metadata panel. */
function row(label: string): HTMLElement {
  const term = within(metadata()).getByText(label, { selector: 'dt' })
  const value = term.nextElementSibling
  if (!(value instanceof HTMLElement)) throw new Error(`no value for ${label}`)
  return value
}

describe('Document page: content and metadata of a published version', () => {
  it('shows the rendered wording of the published version, not only its metadata', async () => {
    const { get } = renderAt(`/documents/${documentId}`)

    const content = await screen.findByTestId('version-content')
    expect(content).toHaveTextContent('Wording of 1.0')
    expect(content).toHaveAttribute('role', 'region')
    expect(content.querySelector('article.dms-document')).not.toBeNull()
    expect(screen.getByText(/rendered from the frozen version/)).toBeInTheDocument()
    expect(screen.queryByText('Draft – not valid')).not.toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/html', {
      params: { path: { id: documentId, versionId: 'v1' } },
    })
  })

  it('lists status, index, number, people, issuing body, validity, approval and template', async () => {
    renderAt(`/documents/${documentId}`)
    await screen.findByTestId('version-metadata')

    expect(within(row('Status')).getByText('Published')).toBeInTheDocument()
    expect(row('Version (index)')).toHaveTextContent('Index 1.0')
    expect(row('Document number')).toHaveTextContent('POL-2026-0007')
    await waitFor(() => {
      expect(row('Author')).toHaveTextContent('Ada Author')
    })
    expect(row('Owner')).toHaveTextContent('Otto Owner')
    expect(row('Issuing body')).toHaveTextContent('IT Governance')
    expect(row('Document class')).toHaveTextContent('policy')
    expect(row('Valid from')).toHaveTextContent('1 Nov 2026')
    expect(row('Valid until')).toHaveTextContent('31 Oct 2028')
    expect(row('Confidentiality')).toHaveTextContent('Internal')
    await waitFor(() => {
      expect(row('Approval')).toHaveTextContent(
        'Dan Deputy on 25 Oct 2026 (on behalf of Anna Approver)',
      )
    })
    expect(row('Template')).toHaveTextContent('Policy, version 3')
    // Class-specific values follow under "Further details"; the panel's own rows are not repeated.
    expect(row('Policy area')).toHaveTextContent('Information security')
    expect(within(metadata()).queryByText('Organizational unit')).not.toBeInTheDocument()
  })

  it('downloads the archived rendition, or a copy with the change history', async () => {
    const downloads = captureDownloads()
    const { get, user } = renderAt(`/documents/${documentId}`)

    const download = await screen.findByRole('button', { name: 'Download PDF' })
    expect(screen.getByText('The archived PDF/A of the approved version.')).toBeInTheDocument()
    await user.click(download)
    await waitFor(() => {
      expect(downloads).toEqual(['rendition'])
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/rendition', {
      params: { path: { id: documentId, versionId: 'v1' } },
      parseAs: 'blob',
    })

    await user.click(screen.getByRole('checkbox', { name: 'Attach change history' }))
    expect(screen.getByText(/the archived PDF\/A stays unchanged/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Download PDF with change history' }))
    await waitFor(() => {
      expect(downloads).toEqual(['rendition', 'copy.pdf'])
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/copy.pdf', {
      params: { path: { id: documentId, versionId: 'v1' }, query: { changelog: true } },
      parseAs: 'blob',
    })
  })
})

describe('Document page: a version in review', () => {
  it('shows the marked draft content and downloads the draft PDF with or without history', async () => {
    current = inReview
    const downloads = captureDownloads()
    const { get, user } = renderAt(`/documents/${documentId}`)

    const content = await screen.findByTestId('version-content')
    expect(content).toHaveTextContent('Wording of 1.1-draft.3')
    expect(screen.getByText('Draft – not valid')).toBeInTheDocument()
    expect(row('Version (index)')).toHaveTextContent('Index 1.1 (draft, working state 3)')
    expect(row('Approval')).toHaveTextContent('Not approved yet')
    expect(
      screen.getByText('Draft – not valid: generated from the last saved state, not archived.'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Download PDF' }))
    await user.click(screen.getByRole('checkbox', { name: 'Attach change history' }))
    await user.click(screen.getByRole('button', { name: 'Download PDF' }))
    await waitFor(() => {
      expect(downloads).toEqual(['draft.pdf', 'draft.pdf'])
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/draft.pdf', {
      params: { path: { id: documentId, versionId: 'v2' }, query: { changelog: false } },
      parseAs: 'blob',
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/versions/{versionId}/draft.pdf', {
      params: { path: { id: documentId, versionId: 'v2' }, query: { changelog: true } },
      parseAs: 'blob',
    })
  })

  it('opens the published version from the version history', async () => {
    current = inReview
    const { router, user } = renderAt(`/documents/${documentId}`)

    await user.click(await screen.findByRole('link', { name: 'Show version 1.0' }))

    expect(router.state.location.search).toEqual({ version: 'v1' })
    expect(await screen.findByText('You are looking at version 1.0.')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByTestId('version-content')).toHaveTextContent('Wording of 1.0')
    })
    expect(within(row('Status')).getByText('Published')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Show the current version' }))
    await waitFor(() => {
      expect(screen.getByTestId('version-content')).toHaveTextContent('Wording of 1.1-draft.3')
    })
  })
})

describe('Document page in German', () => {
  it('labels the metadata and the download in German', async () => {
    setLocale('de')
    renderAt(`/documents/${documentId}`)

    expect(await screen.findByRole('button', { name: 'PDF herunterladen' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Änderungshistorie anhängen' })).toBeInTheDocument()
    await screen.findByTestId('version-metadata')
    expect(row('Herausgebende Stelle')).toHaveTextContent('IT Governance')
    expect(row('Version (Index)')).toHaveTextContent('Index 1.0')
    expect(row('Dokumentnummer')).toHaveTextContent('POL-2026-0007')
    expect(row('Vertraulichkeit')).toHaveTextContent('Intern')
    expect(row('Gültig ab')).toHaveTextContent('01.11.2026')
    expect(screen.getByText('Inhalt')).toBeInTheDocument()
  })
})
