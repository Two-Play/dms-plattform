import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import type { ComplianceOverview, OverdueReview, VersionRate } from '@/api/compliance'
import { ApiClientProvider } from '@/api/context'
import type { Me } from '@/api/me'
import { createAppRouter } from '@/router'
import { parseCompliancePageSearch } from './compliance-params'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'officer', name: 'Carla Compliance' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, headers: Record<string, string> = {}): Reply =>
  Promise.resolve({
    data,
    error: undefined,
    response: new Response(null, { status: 200, headers }),
  })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

const overview: ComplianceOverview = {
  today: '2026-10-29',
  overdueReviews: 3,
  overdueByUnit: [{ unit: 'HR', count: 2 }, { count: 1 }],
  overdueByOwner: [
    { owner: 'owner-a', count: 2 },
    { owner: 'owner-b', count: 1 },
  ],
  acknowledgements: { versions: 2, audience: 12, acknowledged: 3, open: 9, rate: 0.25 },
  acknowledgementsByUnit: [
    { unit: 'HR', rate: { versions: 1, audience: 7, acknowledged: 1, open: 6, rate: 1 / 7 } },
    { unit: 'Finance', rate: { versions: 1, audience: 5, acknowledged: 2, open: 3, rate: 0.4 } },
  ],
  documentsWithoutOwner: 2,
  versionsWithDeviations: 1,
  deviations: 4,
  orphanedDocuments: 1,
}

const hrReview: OverdueReview = {
  documentId: 'd1',
  versionId: 'v1',
  title: 'HR policy',
  versionLabel: '1.0',
  classPath: ['policy'],
  unit: 'HR',
  owner: 'owner-a',
  status: 'ReviewDue',
  dueOn: '2026-10-24',
  daysOverdue: 5,
}

const overdue: OverdueReview[] = [hrReview]

const rates: VersionRate[] = [
  {
    documentId: 'd1',
    versionId: 'v1',
    title: 'HR policy',
    versionLabel: '1.0',
    classPath: ['policy'],
    unit: 'HR',
    owner: 'owner-a',
    audience: 7,
    acknowledged: 1,
    open: 6,
    rate: 1 / 7,
  },
]

let me: Me
let reviews: OverdueReview[]
let notices: () => Reply
let verification: () => Reply
let orphans: () => Reply

const intactRun = {
  lastRunAt: '2026-10-29T03:00:00Z',
  result: 'intact',
  stale: false,
  staleAfterDays: 3,
  entriesChecked: 1234,
  checkpointsCompared: true,
}

beforeEach(() => {
  me = { userId: 'officer', tenantId: 'tenant-1', roles: ['ComplianceOfficer'] }
  reviews = overdue
  verification = () => ok(intactRun)
  orphans = () =>
    ok([
      {
        documentId: 'd4',
        versionId: 'v4',
        title: 'Left behind',
        versionLabel: '1.0',
        classPath: ['policy'],
        unit: 'HR',
        owner: 'owner-gone',
        publishedAt: '2026-01-01T08:00:00Z',
      },
    ])
  notices = () =>
    ok({
      versionId: 'v1',
      kind: 'reminder',
      sentAt: '2026-10-29T10:00:00Z',
      recipients: 2,
      nextNoticeAt: '2026-10-30T10:00:00Z',
    })
})

afterEach(() => {
  vi.restoreAllMocks()
})

function captureDownloads(): string[] {
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:list'), revokeObjectURL: vi.fn() })
  const downloads: string[] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    downloads.push(this.download)
  })
  return downloads
}

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: { params?: { query?: Record<string, string> } }) => {
    switch (p) {
      case '/api/v1/me':
        return ok(me)
      case '/api/v1/compliance/overview':
        return ok(overview)
      case '/api/v1/compliance/overdue-reviews':
        return ok(init?.params?.query?.unit === 'Finance' ? [] : reviews)
      case '/api/v1/compliance/acknowledgements':
        return ok(rates)
      case '/api/v1/compliance/documents-without-owner':
        return ok([
          {
            documentId: 'd2',
            versionId: 'v2',
            title: 'Orphan policy',
            versionLabel: '2.0',
            classPath: ['policy'],
            publishedAt: '2026-01-01T08:00:00Z',
          },
        ])
      case '/api/v1/compliance/audit-verification':
        return verification()
      case '/api/v1/compliance/orphaned-documents':
        return orphans()
      case '/api/v1/compliance/deviations':
        return ok([
          {
            documentId: 'd3',
            versionId: 'v3',
            title: 'Supplier contract',
            versionLabel: '1.0',
            classPath: ['contract'],
            unit: 'Legal',
            owner: 'owner-b',
            deviations: 4,
          },
        ])
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: { body?: { reason?: string } }) => {
    switch (p) {
      case '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open':
        return init?.body?.reason
          ? ok({
              documentId: 'd1',
              versionId: 'v1',
              title: 'HR policy',
              versionLabel: '1.0',
              purpose: 'Escalation',
              audience: 7,
              acknowledged: 1,
              open: ['reader-hr-2', 'reader-all'],
            })
          : failed(403, { code: 'purpose_required', message: 'State the purpose.' })
      case '/api/v1/compliance/overdue-reviews/{documentId}/{versionId}/notices':
        return notices()
      case '/api/v1/compliance/exports/{list}':
        return ok(new Blob(['x']), { 'Content-Disposition': 'attachment; filename="list.csv"' })
      case '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open/export':
        return ok(new Blob(['x']), { 'Content-Disposition': 'attachment; filename="names.csv"' })
      case '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-reminders':
        return ok({ versionId: 'v1', sentAt: 'x', reminded: 6, nextReminderAt: 'y' })
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, user: userEvent.setup() }
}

describe('compliance cockpit', () => {
  it('shows the navigation entry only to compliance officers', async () => {
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(await within(nav).findByRole('link', { name: 'Compliance' })).toBeInTheDocument()
  })

  it('hides the navigation entry from other roles', async () => {
    me = { userId: 'reader', tenantId: 'tenant-1', roles: ['Reader'] }
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await within(nav).findByRole('link', { name: 'Tasks' })
    expect(within(nav).queryByRole('link', { name: 'Compliance' })).not.toBeInTheDocument()
  })

  it('shows the key figures and the figures per unit and owner', async () => {
    renderAt('/compliance')

    expect(
      await screen.findByRole('link', { name: 'Overdue reviews: 3. Show the list' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Acknowledgement rate: 25%. Show the list' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Open acknowledgements: 9. Show the list' }),
    ).toBeInTheDocument()
    const byUnit = screen.getByRole('table', { name: 'Overdue reviews by organizational unit' })
    expect(within(byUnit).getByText('No unit')).toBeInTheDocument()
    const rateTable = screen.getByRole('table', {
      name: 'Acknowledgement rate by organizational unit',
    })
    expect(
      within(rateTable).getByRole('link', { name: 'Acknowledgement rate 14% in HR' }),
    ).toBeInTheDocument()
  })

  it('leads from a figure to the filtered list and back to all', async () => {
    const { router, get, user } = renderAt('/compliance')

    await user.click(await screen.findByRole('link', { name: '2 overdue in HR' }))

    expect(router.state.location.search).toEqual({ view: 'reviews', unit: 'HR' })
    const list = await screen.findByRole('table', { name: 'Overdue reviews' })
    expect(within(list).getByRole('link', { name: 'HR policy' })).toBeInTheDocument()
    expect(within(list).getByText('5 days')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/compliance/overdue-reviews', {
      params: { query: { unit: 'HR', owner: undefined } },
    })
    expect(screen.getByText('Filtered by unit HR')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Clear filter' }))
    expect(router.state.location.search).toEqual({ view: 'reviews' })
  })

  it('filters by versions without unit', async () => {
    const { router, get, user } = renderAt('/compliance')

    await user.click(await screen.findByRole('link', { name: '1 overdue in No unit' }))

    expect(router.state.location.search).toEqual({ view: 'reviews', unit: '' })
    await screen.findByRole('table', { name: 'Overdue reviews' })
    expect(get).toHaveBeenCalledWith('/api/v1/compliance/overdue-reviews', {
      params: { query: { unit: '', owner: undefined } },
    })
  })

  it('asks for purpose and reason before it shows who has not acknowledged', async () => {
    const { post, user } = renderAt('/compliance?view=acknowledgements')

    const list = await screen.findByRole('table', { name: 'Acknowledgement rate per version' })
    expect(within(list).getByText('1 of 7')).toBeInTheDocument()
    await user.click(
      within(list).getByRole('button', { name: 'Who has not acknowledged HR policy 1.0' }),
    )

    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Show names' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('State why you need the names.')
    expect(post).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('radio', { name: 'Reminder to the readers' }))
    await user.type(within(dialog).getByLabelText('Reason *'), 'Rate below target')
    await user.click(within(dialog).getByRole('button', { name: 'Show names' }))

    const names = await within(dialog).findByRole('list', {
      name: 'Readers without acknowledgement',
    })
    expect(
      within(names)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['reader-hr-2', 'reader-all'])
    // Purpose and reason travel in the body, never in the URL (ADR 0060).
    expect(post).toHaveBeenCalledWith(
      '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open',
      {
        params: { path: { documentId: 'd1', versionId: 'v1' } },
        body: { purpose: 'reminder', reason: 'Rate below target' },
      },
    )
  })

  it('says when the verification of the audit chain never ran', async () => {
    verification = () => ok({ stale: false, staleAfterDays: 3 })
    renderAt('/compliance')

    expect(await screen.findByText(/has never run/)).toBeInTheDocument()
  })

  it('shows an intact run with its date', async () => {
    renderAt('/compliance')

    expect(await screen.findByText('Audit chain intact')).toBeInTheDocument()
    expect(screen.getByText(/1,234 entries checked/)).toBeInTheDocument()
    expect(screen.queryByText(/out of date/)).not.toBeInTheDocument()
    expect(screen.queryByText(/checkpoints could not be read/)).not.toBeInTheDocument()
  })

  it('marks a stale run and one that could not read the checkpoints', async () => {
    verification = () => ok({ ...intactRun, stale: true, checkpointsCompared: false })
    renderAt('/compliance')

    expect(await screen.findByText(/more than 3 days old/)).toBeInTheDocument()
    expect(screen.getByText(/checkpoints could not be read/)).toBeInTheDocument()
  })

  it('shows a violation by kind and position, even if it is stale', async () => {
    verification = () =>
      ok({
        ...intactRun,
        result: 'violated',
        stale: true,
        violationKind: 'HashMismatch',
        violationPosition: 17,
        violatedSince: '2026-10-20T03:00:00Z',
      })
    renderAt('/compliance')

    expect(await screen.findByText('Violation of the audit chain found')).toBeInTheDocument()
    expect(screen.getByText(/An entry does not match its hash at position 17/)).toBeInTheDocument()
    expect(screen.getByText(/more than 3 days old/)).toBeInTheDocument()
  })

  it('offers a retry when the verification cannot be loaded, and keeps the figures', async () => {
    verification = () => failed(500, { code: 'internal', message: 'boom' })
    const { user } = renderAt('/compliance')

    const retry = await screen.findByRole('button', { name: 'Try again' })
    expect(await screen.findByText('Overdue reviews')).toBeInTheDocument()

    verification = () => ok(intactRun)
    await user.click(retry)
    expect(await screen.findByText('Audit chain intact')).toBeInTheDocument()
  })

  it('leaves the refusal of the cockpit to the figures', async () => {
    verification = () => failed(403, { code: 'forbidden', message: 'no' })
    renderAt('/compliance')

    await screen.findByRole('link', { name: 'Overdue reviews: 3. Show the list' })
    expect(screen.queryByRole('heading', { name: 'Audit chain verification' })).toBeNull()
  })

  it('leads to the orphaned documents with their owner and exports them', async () => {
    const downloads = captureDownloads()
    const { post, user } = renderAt('/compliance')

    await user.click(
      await screen.findByRole('link', { name: 'Orphaned documents: 1. Show the list' }),
    )
    const table = await screen.findByRole('table', { name: 'Orphaned documents' })
    expect(within(table).getByRole('link', { name: 'Left behind' })).toBeInTheDocument()
    expect(within(table).getByText('owner-gone')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Export Orphaned documents as CSV' }))
    await waitFor(() => {
      expect(downloads).toEqual(['list.csv'])
    })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/compliance/exports/{list}',
      expect.objectContaining({
        params: expect.objectContaining({
          path: { list: 'orphaned-documents' },
        }) as unknown,
      }),
    )
  })

  it('says when no document is orphaned', async () => {
    orphans = () => ok([])
    renderAt('/compliance?view=orphaned')
    expect(await screen.findByText('No orphaned documents.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export Orphaned documents as CSV' })).toBeDisabled()
  })

  it('offers a retry when the orphaned documents cannot be loaded', async () => {
    orphans = () => failed(500, { code: 'internal', message: 'boom' })
    renderAt('/compliance?view=orphaned')
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('leads to the documents without owner and the deviations', async () => {
    const { user } = renderAt('/compliance')

    await user.click(
      await screen.findByRole('link', { name: 'Documents without owner: 2. Show the list' }),
    )
    const unowned = await screen.findByRole('table', { name: 'Documents without owner' })
    expect(within(unowned).getByRole('link', { name: 'Orphan policy' })).toBeInTheDocument()

    await user.click(
      screen.getByRole('link', { name: 'Deviations from standard clauses: 4. Show the list' }),
    )
    const deviating = await screen.findByRole('table', { name: 'Deviations from standard clauses' })
    expect(within(deviating).getByRole('link', { name: 'Supplier contract' })).toBeInTheDocument()
    expect(within(deviating).getByText('4')).toBeInTheDocument()
  })

  it('exports the list with the filters of the screen', async () => {
    const downloads = captureDownloads()
    const { post, user } = renderAt('/compliance?view=reviews&unit=HR')

    await screen.findByRole('table', { name: 'Overdue reviews' })
    await user.click(screen.getByRole('button', { name: 'Export Overdue reviews as CSV' }))

    await waitFor(() => {
      expect(downloads).toEqual(['list.csv'])
    })
    expect(post).toHaveBeenCalledWith('/api/v1/compliance/exports/{list}', {
      params: {
        path: { list: 'overdue-reviews' },
        query: { unit: 'HR', owner: undefined, document: undefined, lang: 'en' },
      },
      parseAs: 'blob',
    })
  })

  it('has nothing to export from an empty list', async () => {
    renderAt('/compliance?view=reviews&unit=Finance')

    await screen.findByText('All periodic reviews in this selection are on time.')
    expect(screen.getByRole('button', { name: 'Export Overdue reviews as CSV' })).toBeDisabled()
  })

  it('asks whom to tell and why before a notice goes out', async () => {
    const { post, user } = renderAt('/compliance?view=reviews')

    const list = await screen.findByRole('table', { name: 'Overdue reviews' })
    await user.click(within(list).getByRole('button', { name: 'Notify about HR policy 1.0' }))

    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('State why you send this notice.')
    expect(post).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('radio', { name: 'Escalate beyond the owner' }))
    await user.type(within(dialog).getByLabelText('Reason *'), 'No reaction')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/compliance/overdue-reviews/{documentId}/{versionId}/notices',
      {
        params: { path: { documentId: 'd1', versionId: 'v1' } },
        body: { kind: 'escalation', reason: 'No reaction' },
      },
    )
  })

  it('names a notice refused as too soon and keeps the dialog open', async () => {
    notices = () => failed(429, { code: 'notice_too_soon', message: 'too soon' })
    const { user } = renderAt('/compliance?view=reviews')

    const list = await screen.findByRole('table', { name: 'Overdue reviews' })
    await user.click(within(list).getByRole('button', { name: 'Notify about HR policy 1.0' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason *'), 'No reaction')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A notice of this kind went out less than 24 hours ago.',
    )
  })

  it('offers only what is possible now', async () => {
    reviews = [
      { ...hrReview, owner: undefined },
      {
        ...hrReview,
        versionId: 'v2',
        title: 'Handbook',
        nextReminderAt: '2026-10-30T10:00:00Z',
        nextEscalationAt: '2026-10-30T10:00:00Z',
      },
    ]
    const { user } = renderAt('/compliance?view=reviews')

    const list = await screen.findByRole('table', { name: 'Overdue reviews' })
    expect(within(list).getByRole('button', { name: 'Notify about Handbook 1.0' })).toBeDisabled()
    await user.click(within(list).getByRole('button', { name: 'Notify about HR policy 1.0' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('radio', { name: 'Remind the owner' })).toBeDisabled()
    expect(within(dialog).getByRole('radio', { name: 'Escalate beyond the owner' })).toBeChecked()
    expect(within(dialog).getByText('This document has no owner to remind.')).toBeInTheDocument()
  })

  it('reminds the readers of a version from the list', async () => {
    const { post, user } = renderAt('/compliance?view=acknowledgements')

    const list = await screen.findByRole('table', { name: 'Acknowledgement rate per version' })
    await user.click(
      within(list).getByRole('button', { name: 'Remind the readers of HR policy 1.0' }),
    )

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(
        '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-reminders',
        { params: { path: { id: 'd1', versionId: 'v1' } } },
      )
    })
  })

  it('exports the names with the purpose and reason already stated', async () => {
    const downloads = captureDownloads()
    const { post, user } = renderAt('/compliance?view=acknowledgements')

    const list = await screen.findByRole('table', { name: 'Acknowledgement rate per version' })
    await user.click(
      within(list).getByRole('button', { name: 'Who has not acknowledged HR policy 1.0' }),
    )
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason *'), 'Rate below target')
    await user.click(within(dialog).getByRole('button', { name: 'Show names' }))
    await user.click(await within(dialog).findByRole('button', { name: 'Download as CSV' }))

    await waitFor(() => {
      expect(downloads).toEqual(['names.csv'])
    })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open/export',
      {
        params: { path: { documentId: 'd1', versionId: 'v1' }, query: { lang: 'en' } },
        body: { purpose: 'escalation', reason: 'Rate below target' },
        parseAs: 'blob',
      },
    )
  })
})

describe('compliance search parameters', () => {
  it('keeps the empty unit (no unit) and drops what does not belong to the view', () => {
    expect(
      parseCompliancePageSearch({ view: 'reviews', unit: '', owner: 'o', document: 'd' }),
    ).toEqual({
      view: 'reviews',
      unit: '',
      owner: 'o',
    })
    expect(
      parseCompliancePageSearch({ view: 'acknowledgements', owner: 'o', document: 'd' }),
    ).toEqual({
      view: 'acknowledgements',
      document: 'd',
    })
    expect(parseCompliancePageSearch({ view: 'other', unit: 'HR' })).toEqual({})
    expect(parseCompliancePageSearch({ view: 'unowned', unit: 'HR', owner: 'o' })).toEqual({
      view: 'unowned',
      unit: 'HR',
    })
    expect(parseCompliancePageSearch({ view: 'deviations', owner: 'o' })).toEqual({
      view: 'deviations',
      owner: 'o',
    })
  })
})
