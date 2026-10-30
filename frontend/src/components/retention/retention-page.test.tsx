import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Me } from '@/api/me'
import {
  filterCandidates,
  type CleanupReport,
  type DestructionCandidate,
  type DestructionItem,
  type DestructionList,
  type DestructionProposal,
  type RetentionRule,
} from '@/api/retention'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'
import { parseRetentionPageSearch } from './retention-params'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'records-2', name: 'Rob Records' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

const assignment = (organizationalUnit?: string) => ({
  role: 'RecordsManager',
  organizationalUnit,
  validFrom: '2026-01-01T00:00:00Z',
})

const recordsManager: Me = {
  userId: 'records-2',
  tenantId: 'tenant-1',
  roles: ['RecordsManager'],
  assignments: [assignment()],
}

const candidate = (over: Partial<DestructionCandidate>): DestructionCandidate => ({
  documentId: 'doc-1',
  versionId: 'v-1',
  title: 'Supply contract North',
  documentNumber: 'VT-2015-0001',
  classKey: 'contract',
  version: '1.0',
  status: 'Superseded',
  retentionRule: 'contract-10y',
  retentionRuleName: 'Contracts, 10 years',
  outOfForceAt: '2015-06-01T10:00:00Z',
  retentionEndsOn: '2025-12-31',
  underLegalHold: false,
  manageable: true,
  ...over,
})

const list: DestructionList = {
  candidates: [
    candidate({}),
    candidate({
      documentId: 'doc-2',
      versionId: 'v-2',
      title: 'Supply contract South',
      documentNumber: undefined,
      underLegalHold: true,
      manageable: false,
    }),
    candidate({
      documentId: 'doc-3',
      versionId: 'v-3',
      title: 'Travel policy',
      documentNumber: 'RL-2015-0003',
      classKey: 'policy',
      retentionRule: 'policy-10y',
      retentionRuleName: 'Policies',
      status: 'Withdrawn',
    }),
  ],
  withoutRule: 2,
  retentionRunning: 5,
}

const pendingItem: DestructionItem = {
  versionId: 'v-9',
  documentId: 'doc-9',
  title: 'Old contract',
  version: '2.0',
  retentionRule: 'contract-10y',
  retentionEndedOn: '2025-12-31',
  outcome: 'Pending',
  files: [],
}

const own: DestructionProposal = {
  id: 'p-own',
  status: 'Proposed',
  reason: 'Retention of 2025 ended',
  proposedBy: 'records-2',
  proposedAt: '2026-10-29T09:00:00Z',
  items: [pendingItem],
  canDecide: false,
}

const other: DestructionProposal = {
  ...own,
  id: 'p-other',
  proposedBy: 'records-1',
  reason: 'Annual destruction run',
  canDecide: true,
}

const approved: DestructionProposal = {
  ...other,
  status: 'Approved',
  canDecide: false,
  decidedBy: 'records-2',
  decidedAt: '2026-10-30T09:00:00Z',
  decisionReason: 'Checked',
  items: [
    {
      ...pendingItem,
      outcome: 'Destroyed',
      outcomeAt: '2026-10-30T09:00:00Z',
      renditionSha256: 'a'.repeat(64),
      contentSha256: 'b'.repeat(64),
      files: [{ name: 'Annex.pdf', role: 'Attachment', sha256: 'c'.repeat(64), released: true }],
    },
    {
      ...pendingItem,
      versionId: 'v-10',
      title: 'Held contract',
      outcome: 'Blocked',
      outcomeDetail: 'LegalHold',
      outcomeAt: '2026-10-30T09:00:00Z',
    },
  ],
}

const rules: RetentionRule[] = [
  {
    key: 'contract-10y',
    name: 'Contracts, 10 years',
    periodMonths: 120,
    trigger: 'EndOfValidity',
    fromYearEnd: true,
    updatedBy: 'records-1',
    updatedAt: '2026-10-01T09:00:00Z',
  },
]

const report: CleanupReport = {
  id: 'run-1',
  dryRun: true,
  startedAt: '2026-10-30T08:00:00Z',
  finishedAt: '2026-10-30T08:00:05Z',
  triggeredBy: 'records-2',
  graceHours: 168,
  summary: {
    scanned: 40,
    orphaned: 3,
    deleted: 2,
    keptLocked: 1,
    keptHeld: 0,
    keptRecent: 0,
    failed: 0,
    filesDeleted: 1,
  },
  entries: [
    { key: 'attachments/t/doc-1/loose', version: 'x1', outcome: 'WouldDelete' },
    {
      key: 'renditions/t/doc-1/v.pdf',
      version: 'x2',
      outcome: 'KeptLocked',
      detail: 'retention until 2030-01-01T00:00:00Z',
    },
  ],
}

let me: Me
let post: ReturnType<typeof vi.fn>
let put: ReturnType<typeof vi.fn>

beforeEach(() => {
  me = recordsManager
})

function renderAt(
  path: string,
  posts: (p: string, body: unknown) => Reply = () => ok({}),
  proposals: DestructionProposal[] = [own, other],
) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok(me)
      case '/api/v1/users':
        return ok([{ id: 'records-1', displayName: 'Rita Records' }])
      case '/api/v1/retention/destruction-list':
        return ok(list)
      case '/api/v1/retention/destruction-proposals':
        return ok(proposals)
      case '/api/v1/retention/rules':
        return ok(rules)
      case '/api/v1/retention/storage-cleanup':
        return ok([report])
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  post = vi.fn((p: string, init?: { body?: unknown }) => posts(p, init?.body))
  put = vi.fn((_p: string, init?: { body?: unknown; params?: { path?: { key?: string } } }) =>
    ok({ ...rules[0], ...(init?.body as object), key: init?.params?.path?.key }),
  )
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
  return { router, get, user: userEvent.setup() }
}

describe('retention navigation', () => {
  it('shows the entry to records managers for the whole tenant only', async () => {
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(await within(nav).findByRole('link', { name: 'Retention' })).toBeInTheDocument()
  })

  it('hides the entry from records managers of a unit and explains the page', async () => {
    me = { ...recordsManager, assignments: [assignment('HR')] }
    renderAt('/retention')
    expect(
      await screen.findByText('Only records managers for the whole organization manage retention.'),
    ).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).queryByRole('link', { name: 'Retention' })).not.toBeInTheDocument()
  })
})

describe('destruction list', () => {
  it('lists the due versions with legal hold and counts; held versions cannot be selected', async () => {
    renderAt('/retention')
    const table = await screen.findByRole('table', { name: 'Versions whose retention ended' })
    expect(within(table).getByText('Supply contract North')).toBeInTheDocument()
    expect(within(table).getByText('VT-2015-0001')).toBeInTheDocument()
    expect(within(table).getByText('Legal hold')).toBeInTheDocument()
    expect(
      within(table).getByRole('checkbox', { name: 'Select version 1.0 of Supply contract South' }),
    ).toBeDisabled()
    expect(
      screen.getByText('2 versions out of force have no retention rule and are never proposed.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('The retention of 5 further versions is still running.'),
    ).toBeInTheDocument()
  })

  it('filters by rule through the URL', async () => {
    const { user, router } = renderAt('/retention')
    await screen.findByRole('table', { name: 'Versions whose retention ended' })
    await user.selectOptions(screen.getByLabelText('Retention rule'), 'policy-10y')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ rule: 'policy-10y' })
    })
    const table = screen.getByRole('table', { name: 'Versions whose retention ended' })
    expect(within(table).getByText('Travel policy')).toBeInTheDocument()
    expect(within(table).queryByText('Supply contract North')).not.toBeInTheDocument()
  })

  it('proposes the selected versions with a reason and opens the proposals', async () => {
    const { user, router } = renderAt('/retention', (p) =>
      p === '/api/v1/retention/destruction-proposals' ? ok(own, 201) : ok({}),
    )
    const propose = await screen.findByRole('button', { name: 'Propose for destruction' })
    expect(propose).toBeDisabled()
    await user.click(
      screen.getByRole('checkbox', { name: 'Select version 1.0 of Supply contract North' }),
    )
    expect(screen.getByText('1 version selected')).toBeInTheDocument()
    await user.click(propose)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Propose' }))
    expect(within(dialog).getByText('State the reason.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Reason *'), 'Retention of 2025 ended')
    await user.click(within(dialog).getByRole('button', { name: 'Propose' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'proposals' })
    })
    expect(post).toHaveBeenCalledWith('/api/v1/retention/destruction-proposals', {
      body: { versionIds: ['v-1'], reason: 'Retention of 2025 ended' },
    })
  })
})

describe('destruction proposals', () => {
  it('asks for a second records manager on the own proposal', async () => {
    renderAt('/retention?tab=proposals', () => ok({}), [own])
    expect(
      await screen.findByText(
        'Your proposal: another records manager approves or rejects it (four eyes).',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve and destroy' })).not.toBeInTheDocument()
  })

  it('approves only with reason and explicit confirmation', async () => {
    const { user } = renderAt('/retention?tab=proposals', () => ok(approved), [other])
    await user.click(await screen.findByRole('button', { name: 'Approve and destroy' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/This cannot be undone/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason *'), 'Checked')
    await user.click(within(dialog).getByRole('button', { name: 'Destroy' }))
    expect(within(dialog).getByText('Confirm the destruction.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.click(
      within(dialog).getByRole('checkbox', {
        name: 'I checked the versions and confirm their destruction',
      }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Destroy' }))
    expect(
      await screen.findByText(
        '1 version destroyed. 1 version was not destroyed (see the outcome).',
      ),
    ).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/retention/destruction-proposals/{id}/approve', {
      params: { path: { id: 'p-other' } },
      body: { reason: 'Checked' },
    })
  })

  it('explains the four-eyes refusal', async () => {
    const { user } = renderAt(
      '/retention?tab=proposals',
      () => failed(403, { code: 'four_eyes', message: 'four eyes' }),
      [other],
    )
    await user.click(await screen.findByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason *'), 'No')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(
      await within(dialog).findByText(
        'You proposed this destruction yourself; another records manager decides it.',
      ),
    ).toBeInTheDocument()
  })

  it('shows the outcome with checksums and why a version was not destroyed', async () => {
    renderAt('/retention?tab=proposals&status=approved', () => ok({}), [approved])
    const table = await screen.findByRole('table', {
      name: 'Versions of the proposal and their outcome',
    })
    expect(within(table).getByText('Destroyed')).toBeInTheDocument()
    expect(within(table).getByText('a'.repeat(64))).toBeInTheDocument()
    expect(within(table).getByText('Annex.pdf (Attachment): removed')).toBeInTheDocument()
    expect(within(table).getByText('Not destroyed')).toBeInTheDocument()
    expect(within(table).getByText('under legal hold')).toBeInTheDocument()
  })
})

describe('retention rules and clean-up', () => {
  it('saves a new rule after checking key, name and period', async () => {
    const { user } = renderAt('/retention?tab=rules')
    expect(await screen.findByRole('cell', { name: 'contract-10y' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '120 months' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Key *'), 'Bad Key')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(
      within(dialog).getByText('Use lower-case letters, digits, ".", "_" and "-" only.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Name the rule.')).toBeInTheDocument()
    expect(put).not.toHaveBeenCalled()

    await user.clear(within(dialog).getByLabelText('Key *'))
    await user.type(within(dialog).getByLabelText('Key *'), 'invoice-10y')
    await user.type(within(dialog).getByLabelText('Name *'), 'Invoices')
    await user.selectOptions(within(dialog).getByLabelText('Period starts with'), 'Creation')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await screen.findByText('Rule invoice-10y saved.')).toBeInTheDocument()
    expect(put).toHaveBeenCalledWith('/api/v1/retention/rules/{key}', {
      params: { path: { key: 'invoice-10y' } },
      body: { name: 'Invoices', periodMonths: 120, trigger: 'Creation', fromYearEnd: true },
    })
  })

  it('checks object storage in a dry run and shows the report', async () => {
    const { user } = renderAt('/retention?tab=cleanup', () => ok(report))
    expect(await screen.findByTestId('cleanup-summary')).toHaveTextContent(
      '40 objects scanned, 3 without reference, 2 would be removed, 1 kept, 1 unused stored files',
    )
    expect(screen.getByRole('cell', { name: 'Kept: Object Lock' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Check (dry run)' }))
    expect(await screen.findByText('Check finished; nothing was removed.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/retention/storage-cleanup', {
      body: { dryRun: true },
    })
  })

  it('is available in German', async () => {
    setLocale('de')
    renderAt('/retention')
    expect(
      await screen.findByRole('heading', { name: 'Aufbewahrung', level: 1 }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Vernichtungsliste' })).toBeInTheDocument()
    expect(
      await screen.findByRole('button', { name: 'Zur Vernichtung vorschlagen' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Frist abgelaufen am' })).toBeInTheDocument()
    expect(screen.getByText('Legal Hold')).toBeInTheDocument()
  })
})

describe('retention helpers', () => {
  it('reads the page parameters', () => {
    expect(parseRetentionPageSearch({ tab: 'rules', status: 'x', q: '  north ' })).toEqual({
      tab: 'rules',
      q: 'north',
    })
    expect(parseRetentionPageSearch({ tab: 'list' })).toEqual({})
  })

  it('filters candidates by class, rule and text', () => {
    const all = list.candidates ?? []
    expect(filterCandidates(all, { class: 'policy' }).map((c) => c.versionId)).toEqual(['v-3'])
    expect(filterCandidates(all, { q: 'vt-2015' }).map((c) => c.versionId)).toEqual(['v-1'])
    expect(
      filterCandidates(all, { rule: 'contract-10y', q: 'south' }).map((c) => c.versionId),
    ).toEqual(['v-2'])
  })
})
