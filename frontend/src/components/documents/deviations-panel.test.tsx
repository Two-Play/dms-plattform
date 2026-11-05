import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Deviation, DeviationChange, DeviationRequest, Deviations } from '@/api/deviations'
import type { DocumentVersionView } from '@/api/partners'
import { setLocale } from '@/i18n'
import { changeText, locationText } from './deviation-labels'
import { DeviationsPanel } from './deviations-panel'
import { DocumentPreview } from './document-preview'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d9'

function version(status = 'Draft'): DocumentVersionView {
  return {
    id: 'v1',
    version: '1.0-draft.2',
    workingState: 2,
    status,
    changeKind: 'Major',
    requiresAcknowledgement: false,
    scope: '',
    classVersion: 1,
    templateId: '7c1e2f0a-0000-4000-8000-000000000001',
    templateVersion: 1,
    content: {
      sections: [
        { id: 'parties', title: 'Parties', locked: true },
        { id: 'liability', title: 'Liability', locked: true },
        { id: 'terms', title: 'Terms', locked: false },
      ],
    },
    values: {},
    createdBy: 'author-1',
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    requiresExtraReview: false,
  }
}

const request = (extra: Partial<DeviationRequest> = {}): DeviationRequest => ({
  id: 'r1',
  sectionId: 'liability',
  reason: 'The customer insists on a cap',
  requestedBy: 'author-1',
  requestedAt: '2026-11-02T09:00:00Z',
  active: true,
  ...extra,
})

const deviation = (extra: Partial<Deviation> = {}): Deviation => ({
  section: 'liability',
  path: [{ type: 'Block', index: 1 }],
  token: 4,
  location: 'liability › block 1 › word 4',
  change: { type: 'TextReplaced', from: '30 days', to: '60 days' },
  authorized: true,
  reasons: ['The customer insists on a cap'],
  ...extra,
})

function report(extra: Partial<Deviations> = {}): Deviations {
  return {
    versionId: 'v1',
    version: '1.0-draft.2',
    status: 'Draft',
    requiresExtraReview: false,
    requests: [],
    deviations: [],
    ...extra,
  }
}

const ok = (data: unknown, status = 200) =>
  Promise.resolve({ data, response: new Response(null, { status }) })
const failed = (status: number, error: unknown) =>
  Promise.resolve({ error, response: new Response(null, { status }) })

type Reply = () => Promise<{ data?: unknown; error?: unknown; response: Response }>

function renderPanel({
  load,
  post,
  editable = true,
  status = 'Draft',
}: {
  load: Reply
  post?: Reply
  editable?: boolean
  status?: string
}) {
  const get = vi.fn((path: string) =>
    path === '/api/v1/documents/{id}/versions/{versionId}/deviations' ? load() : ok([]),
  )
  const postFn = vi.fn(() => (post ? post() : ok(report())))
  const client = { GET: get, POST: postFn } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <DeviationsPanel documentId={documentId} version={version(status)} editable={editable} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post: postFn, user: userEvent.setup() }
}

const row = (title: string) => {
  const found = screen
    .getAllByTestId('locked-section')
    .find((element) => within(element).queryByText(title))
  if (!found) throw new Error(`no row for ${title}`)
  return found
}

beforeEach(() => {
  setLocale('en')
})

afterEach(() => {
  setLocale('en')
})

describe('Deviations panel on a draft', () => {
  it('lists the locked sections with their state, not the editable ones', async () => {
    renderPanel({
      load: () => ok(report({ requests: [request({ basedOn: 'r0' })] })),
    })

    await screen.findByText('Locked sections')
    expect(screen.getAllByTestId('locked-section')).toHaveLength(2)
    expect(screen.queryByText('Terms')).not.toBeInTheDocument()
    expect(within(row('Parties')).getByText('Standard text')).toBeInTheDocument()
    const requested = within(row('Liability'))
    expect(requested.getByText('Deviation requested')).toBeInTheDocument()
    expect(requested.getByText('The customer insists on a cap')).toBeInTheDocument()
    expect(requested.getByText(/Carried over from the previous version/)).toBeInTheDocument()
    expect(requested.getByTitle('author-1')).toBeInTheDocument()
    // Each row offers what can be done with it, named for screen readers.
    expect(
      screen.getByRole('button', { name: 'Request deviation for Parties' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Withdraw request for Liability' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Request deviation for Liability' }),
    ).not.toBeInTheDocument()
  })

  it('says that the text itself cannot be changed here', async () => {
    renderPanel({ load: () => ok(report()) })
    expect(await screen.findByText(/cannot be changed on this page yet/)).toBeInTheDocument()
  })

  it('requests a deviation by keyboard and shows the new state', async () => {
    let current = report()
    const { post, user } = renderPanel({
      load: () => ok(current),
      post: () => {
        current = report({
          requests: [request({ id: 'r2', sectionId: 'parties', reason: 'Affiliates' })],
        })
        return ok(current)
      },
    })

    const action = await screen.findByRole('button', { name: 'Request deviation for Parties' })
    action.focus()
    await user.keyboard('{Enter}')
    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByRole('heading', { name: 'Request a deviation for Parties' }),
    ).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Reason'), '  Affiliates  ')
    await user.tab()
    await user.tab()
    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/deviation-requests', {
      params: { path: { id: documentId } },
      body: { sectionId: 'parties', reason: 'Affiliates' },
    })
    expect(await within(row('Parties')).findByText('Deviation requested')).toBeInTheDocument()
    expect(within(row('Parties')).getByText('Affiliates')).toBeInTheDocument()
    await waitFor(() => {
      expect(row('Parties')).toHaveFocus()
    })
  })

  it('asks for a reason and refuses one that is too long, without calling the server', async () => {
    const { post, user } = renderPanel({ load: () => ok(report()) })
    await user.click(await screen.findByRole('button', { name: 'Request deviation for Parties' }))
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Please give a reason.')
    expect(within(dialog).getByLabelText('Reason')).toHaveAttribute('aria-invalid', 'true')

    await user.click(within(dialog).getByLabelText('Reason'))
    await user.paste('x'.repeat(4001))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('At most 4,000 characters')
    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))
    expect(post).not.toHaveBeenCalled()
  })

  it('keeps the reason when the request fails, and succeeds on the next try', async () => {
    const replies: Reply[] = [
      () => failed(500, { code: 'internal', message: 'Something broke.' }),
      () => ok(report({ requests: [request({ sectionId: 'parties', reason: 'Affiliates' })] })),
    ]
    const { user } = renderPanel({
      load: () => ok(report()),
      post: () => (replies.shift() as Reply)(),
    })
    await user.click(await screen.findByRole('button', { name: 'Request deviation for Parties' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Affiliates')
    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The deviation was not requested: Something broke.',
    )
    expect(within(dialog).getByLabelText('Reason')).toHaveValue('Affiliates')

    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('keeps the form and the reason when someone else requested it first', async () => {
    let loads = 0
    const { get, user } = renderPanel({
      load: () => {
        loads += 1
        return ok(
          report({
            requests: loads > 1 ? [request({ sectionId: 'parties', reason: 'Theirs' })] : [],
          }),
        )
      },
      post: () =>
        failed(409, {
          code: 'conflict',
          message: "section 'parties' already has an active deviation request",
        }),
    })
    await user.click(await screen.findByRole('button', { name: 'Request deviation for Parties' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Mine')
    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'already has an active deviation request The list has been updated.',
    )
    await waitFor(() => {
      expect(loads).toBeGreaterThan(1)
    })
    expect(get).toHaveBeenCalled()
    expect(within(dialog).getByLabelText('Reason')).toHaveValue('Mine')
  })

  it('names a refusal by the server in the language of the UI', async () => {
    const { user } = renderPanel({
      load: () => ok(report()),
      post: () => failed(403, { code: 'forbidden', message: 'You are not allowed to do this.' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Request deviation for Parties' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Affiliates')
    await user.click(within(dialog).getByRole('button', { name: 'Send request' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'You may not request deviations for this document.',
    )
  })

  it('withdraws a request after a warning, and keeps it visible as withdrawn', async () => {
    let current = report({
      requests: [request()],
      deviations: [deviation()],
      requiresExtraReview: true,
    })
    const { post, user } = renderPanel({
      load: () => ok(current),
      post: () => {
        current = report({
          requests: [
            request({
              active: false,
              withdrawnBy: 'author-1',
              withdrawnAt: '2026-11-03T09:00:00Z',
            }),
          ],
          deviations: [deviation({ authorized: false, reasons: [] })],
        })
        return ok(current)
      },
    })
    expect(await screen.findByText('Additional review step')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Withdraw request for Liability' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Without a request the draft cannot be submitted')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/deviation-requests/{requestId}/withdraw',
      { params: { path: { id: documentId, requestId: 'r1' } } },
    )
    expect(await screen.findByText('Withdrawn requests')).toBeInTheDocument()
    expect(screen.getByText(/Withdrawn by/)).toBeInTheDocument()
    expect(within(row('Liability')).getByText('Changed without a request')).toBeInTheDocument()
    expect(screen.queryByText('Additional review step')).not.toBeInTheDocument()
  })

  it('shows why a withdrawal failed and lets the author cancel', async () => {
    const { user } = renderPanel({
      load: () => ok(report({ requests: [request()] })),
      post: () => failed(409, { code: 'conflict', message: 'already withdrawn.' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Withdraw request for Liability' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('You can request a deviation for this section again later.')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The request was not withdrawn: already withdrawn.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})

describe('Deviation report', () => {
  it('shows location, change, reason and authorization to reviewers, without actions', async () => {
    renderPanel({
      editable: false,
      status: 'InReview',
      load: () =>
        ok(
          report({
            status: 'InReview',
            requiresExtraReview: true,
            requests: [request()],
            deviations: [
              deviation(),
              deviation({
                section: 'parties',
                path: [],
                token: undefined,
                change: { type: 'SectionRemoved' },
                authorized: false,
                reasons: [],
              }),
            ],
          }),
        ),
    })

    const table = await screen.findByTestId('deviation-report')
    const rows = within(table).getAllByRole('row')
    expect(within(rows[0] as HTMLElement).getAllByRole('columnheader')).toHaveLength(4)
    const [location, change, reason, authorized] = within(rows[1] as HTMLElement).getAllByRole(
      'cell',
    )
    expect(location).toHaveTextContent('Liability › Block 2 › Word 5')
    expect(change).toHaveTextContent('Text replaced')
    expect(change).toHaveTextContent('Before: 30 days')
    expect(change).toHaveTextContent('After: 60 days')
    expect(reason).toHaveTextContent('The customer insists on a cap')
    expect(authorized).toHaveTextContent('Yes')

    const second = within(rows[2] as HTMLElement).getAllByRole('cell')
    expect(second[0]).toHaveTextContent('Parties')
    expect(second[1]).toHaveTextContent('Section removed')
    expect(second[2]).toHaveTextContent('No reason recorded')
    expect(second[3]).toHaveTextContent('No')

    expect(screen.getByText('Additional review step')).toBeInTheDocument()
    expect(screen.getByText('Deviation requests')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Request deviation/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Withdraw request/ })).not.toBeInTheDocument()
  })

  it('says so when there are no deviations', async () => {
    renderPanel({ editable: false, status: 'Published', load: () => ok(report()) })
    expect(await screen.findByText('No deviations')).toBeInTheDocument()
    expect(screen.queryByTestId('locked-section')).not.toBeInTheDocument()
  })

  it('shows a loading state until the report arrives', async () => {
    let finish: (value: Awaited<ReturnType<Reply>>) => void = () => undefined
    renderPanel({
      load: () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    })
    expect(screen.getByRole('status')).toHaveTextContent('Loading the deviation report…')
    finish(await ok(report()))
    expect(await screen.findByText('No deviations')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('offers to try again when loading fails, and hides the actions until then', async () => {
    const replies: Reply[] = [
      () => failed(500, { code: 'internal', message: 'Something broke.' }),
      () => ok(report()),
    ]
    const { user } = renderPanel({ load: () => (replies.shift() as Reply)() })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Request deviation/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No deviations')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Request deviation for Parties' }),
    ).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel({
      load: () => ok(report({ deviations: [deviation()], requests: [request()] })),
    })
    expect(await screen.findByText('Abweichungsbericht')).toBeInTheDocument()
    expect(screen.getByText('Liability › Block 2 › Wort 5')).toBeInTheDocument()
    expect(screen.getByText('Text ersetzt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Antrag für Liability zurückziehen' })).toBeEnabled()
  })
})

describe('Texts of the report', () => {
  const placeholder = { path: 'partner.name', format: 'plain' }

  it.each<[DeviationChange, string, string | undefined, string | undefined]>([
    [{ type: 'TextInserted', text: 'new' }, 'Text inserted', undefined, 'new'],
    [{ type: 'TextDeleted', text: 'old' }, 'Text deleted', 'old', undefined],
    [{ type: 'FormattingChanged', text: 'bold' }, 'Formatting changed', undefined, 'bold'],
    [{ type: 'ConditionChanged', from: 'a', to: 'b' }, 'Condition changed', 'a', 'b'],
    [
      { type: 'PlaceholderInserted', placeholder },
      'Placeholder inserted',
      undefined,
      '{{partner.name|plain}}',
    ],
    [
      { type: 'PlaceholderRemoved', placeholder: { path: 'x' } },
      'Placeholder removed',
      '{{x}}',
      undefined,
    ],
    [
      { type: 'PlaceholderChanged', from: { path: 'x' }, to: { path: 'y' } },
      'Placeholder changed',
      '{{x}}',
      '{{y}}',
    ],
    [
      {
        type: 'RepeatChanged',
        fromCollection: 'rows',
        fromItem: 'r',
        toCollection: 'lines',
        toItem: 'l',
      },
      'Repetition changed',
      'r ∈ rows',
      'l ∈ lines',
    ],
    [
      { type: 'ClauseChanged', fromKey: 'cap', fromVersion: 1, toKey: 'cap', toVersion: 2 },
      'Clause changed',
      'cap v1',
      'cap v2',
    ],
    [{ type: 'HeadingLevelChanged', from: 1, to: 2 }, 'Heading level changed', '1', '2'],
    [{ type: 'HeaderRowsChanged', from: 0, to: 1 }, 'Header rows changed', '0', '1'],
    [{ type: 'SectionRenamed', from: 'A', to: 'B' }, 'Section renamed', 'A', 'B'],
    [{ type: 'SectionMoved' }, 'Section moved', undefined, undefined],
    [{ type: 'SectionUnlocked' }, 'Section unlocked', undefined, undefined],
  ])('%j', (change, label, before, after) => {
    expect(changeText(change)).toEqual({ label, before, after })
  })

  it('reads the words of inserted and deleted blocks', () => {
    const blocks = [
      {
        type: 'Paragraph',
        content: [
          { type: 'Text', value: 'Between ' },
          { type: 'Placeholder', path: 'partner' },
        ],
      },
    ]
    const change = { type: 'BlocksInserted', blocks } as unknown as DeviationChange
    expect(changeText(change).after).toBe('Between {{partner}}')
    expect(changeText({ type: 'BlocksDeleted' }).before).toBeUndefined()
  })

  it('counts positions from one in every kind of location', () => {
    const at = (path: Deviation['path']) =>
      locationText(deviation({ path, token: undefined }), 'Liability')
    expect(
      at([
        { type: 'Block', index: 0 },
        { type: 'Item', index: 2 },
      ]),
    ).toBe('Liability › Block 1 › Item 3')
    expect(
      at([
        { type: 'Row', index: 1 },
        { type: 'Cell', row: 1, column: 0 },
      ]),
    ).toBe('Liability › Row 2 › Cell 2/1')
    expect(at([{ type: 'Then' }])).toBe('Liability › Condition met')
    expect(at([{ type: 'Otherwise' }])).toBe('Liability › Condition not met')
    expect(at([{ type: 'Body' }])).toBe('Liability › Repeated content')
  })
})

describe('Draft preview', () => {
  it('names locked sections through the marks of the preview', () => {
    render(
      <DocumentPreview
        preview={{ html: '<section class="dms-section" data-locked="true"></section>' }}
        updating={false}
        error={null}
        onFocusField={() => undefined}
      />,
    )
    const preview = screen.getByTestId('document-preview')
    expect(preview).toHaveClass('dms-locked-marks')
    expect(preview.style.getPropertyValue('--dms-locked-label')).toBe('"Locked"')
  })
})
