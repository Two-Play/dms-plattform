import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  AcknowledgementOverview,
  RecipientList,
  VersionAcknowledgementOverview,
  ReminderSent,
} from '@/api/acknowledgement-overview'
import { recipientsCsv } from '@/api/acknowledgement-overview'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { setLocale } from '@/i18n'
import { getToasts } from '@/lib/toast'
import { AcknowledgementOverviewPanel } from './acknowledgement-overview-panel'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const versionId = '7c1e2f0a-0000-4000-8000-0000000000v1'

const published: VersionAcknowledgementOverview = {
  versionId,
  versionLabel: '2.0',
  status: 'Published',
  publishedAt: '2026-10-01T08:00:00Z',
  recipients: {
    everyReader: false,
    roles: [],
    groups: ['board'],
    units: ['HR'],
    source: 'class',
    sourceClassId: 'c1',
  },
  reminderDays: 7,
  audience: 3,
  acknowledged: 1,
  open: 2,
  rate: 1 / 3,
}

const overview: AcknowledgementOverview = {
  documentId,
  title: 'Leave policy',
  versions: [published],
}

const recipients: RecipientList = {
  documentId,
  versionId,
  title: 'Leave policy',
  versionLabel: '2.0',
  purpose: 'Reminder',
  audience: 3,
  acknowledged: 1,
  open: 2,
  recipients: [
    { userId: 'board-1', displayName: 'Berta Brandt', status: 'open' },
    {
      userId: 'hr-1',
      displayName: 'Hanna Hansen',
      status: 'done',
      acknowledgedAt: '2026-10-02T09:30:00Z',
    },
    { userId: 'hr-2', status: 'open' },
  ],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

function renderPanel({
  load = () => ok(overview),
  remind = () =>
    ok(
      {
        versionId,
        sentAt: '2026-10-29T10:00:00Z',
        reminded: 2,
        nextReminderAt: '2026-10-30T10:00:00Z',
      } satisfies ReminderSent,
      201,
    ),
}: { load?: () => Reply; remind?: () => Reply } = {}) {
  const get = vi.fn((path: string) =>
    path.endsWith('acknowledgement-recipients') ? ok(recipients) : load(),
  )
  const post = vi.fn(remind)
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <AcknowledgementOverviewPanel documentId={documentId} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, view, user: userEvent.setup() }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Acknowledgement overview for owners (DMS-100)', () => {
  it('shows figures, recipients and the reminder interval without names', async () => {
    renderPanel()
    const figures = await screen.findByRole('region', { name: 'Read confirmations of version 2.0' })
    expect(within(figures).getByText('Groups: board · Units: HR')).toBeInTheDocument()
    expect(within(figures).getByText('(set by the document class)')).toBeInTheDocument()
    expect(within(figures).getByText('every 7 days')).toBeInTheDocument()
    expect(within(figures).getByText('(33%)')).toBeInTheDocument()
    expect(screen.queryByText('Berta Brandt')).not.toBeInTheDocument()
  })

  it('renders nothing for readers who may not see the overview', async () => {
    const { get, view } = renderPanel({
      load: () => failed(403, { code: 'forbidden', message: 'no' }),
    })
    await waitFor(() => {
      expect(get).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(view.container).toBeEmptyDOMElement()
    })
  })

  it('shows an empty state when no valid version asks for confirmation', async () => {
    renderPanel({ load: () => ok({ ...overview, versions: [] }) })
    expect(
      await screen.findByText('No valid version of this document asks for read confirmation.'),
    ).toBeInTheDocument()
  })

  it('sends a reminder and confirms it with a toast', async () => {
    const { post, user } = renderPanel()
    await user.click(await screen.findByRole('button', { name: 'Send a reminder for version 2.0' }))
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-reminders',
      { params: { path: { id: documentId, versionId } } },
    )
    await waitFor(() => {
      expect(getToasts().map((t) => t.message)).toContain('Reminder sent to 2 people.')
    })
  })

  it('explains a refused reminder when one was sent less than a day ago', async () => {
    const { user } = renderPanel({
      remind: () => failed(429, { code: 'reminder_too_soon', message: 'too soon' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Send a reminder for version 2.0' }))
    await waitFor(() => {
      expect(getToasts()).toContainEqual(
        expect.objectContaining({
          message: 'A reminder was sent less than a day ago; try again tomorrow.',
          variant: 'error',
        }),
      )
    })
  })

  it('disables the reminder while the last one is less than a day old', async () => {
    renderPanel({
      load: () =>
        ok({
          ...overview,
          versions: [
            {
              ...published,
              lastReminderAt: '2026-10-29T08:00:00Z',
              nextReminderAt: '2026-10-30T08:00:00Z',
            },
          ],
        }),
    })
    expect(
      await screen.findByRole('button', { name: 'Send a reminder for version 2.0' }),
    ).toBeDisabled()
    expect(screen.getByText(/Next reminder possible from/)).toBeInTheDocument()
  })

  it('asks for a reason before names, then lists them filtered and exports CSV', async () => {
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() })
    const downloads: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download)
    })
    const { get, user } = renderPanel()
    await user.click(
      await screen.findByRole('button', { name: 'Show who must confirm version 2.0' }),
    )
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Show people' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Please state a reason.')
    expect(get).not.toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-recipients',
      expect.anything(),
    )

    await user.type(within(dialog).getByLabelText('Reason *'), 'Policy rollout')
    await user.click(within(dialog).getByRole('button', { name: 'Show people' }))
    expect(get).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-recipients',
      {
        params: {
          path: { id: documentId, versionId },
          query: { purpose: 'reminder', reason: 'Policy rollout' },
        },
      },
    )
    const table = await within(dialog).findByRole('table', { name: 'Recipients of version 2.0' })
    // Open recipients first; an unknown name falls back to the user id.
    expect(within(table).getByText('Berta Brandt')).toBeInTheDocument()
    expect(within(table).getByText('hr-2')).toBeInTheDocument()
    expect(within(table).queryByText('Hanna Hansen')).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('radio', { name: 'Confirmed (1)' }))
    expect(within(table).getByText('Hanna Hansen')).toBeInTheDocument()
    expect(within(table).queryByText('Berta Brandt')).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Export the list as CSV file' }))
    expect(downloads).toEqual(['read-confirmations-Leave-policy-2.0-done.csv'])
  })

  it('writes CSV with escaped cells and translated status', () => {
    const csv = recipientsCsv(
      {
        ...recipients,
        recipients: [{ userId: 'u1', displayName: 'Doe; "Jane"', status: 'open' }],
      },
      { name: 'Name', userId: 'User', status: 'Status', acknowledgedAt: 'At' },
      (s) => (s === 'open' ? 'Offen' : 'Bestätigt'),
    )
    expect(csv).toBe('Name;User;Status;At\r\n"Doe; ""Jane""";u1;Offen;')
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel()
    expect(await screen.findByText('Kenntnisnahmen')).toBeInTheDocument()
    expect(
      await screen.findByRole('button', { name: 'Erinnerung für Version 2.0 senden' }),
    ).toHaveTextContent('Erinnerung senden')
    expect(screen.getByText('alle 7 Tage')).toBeInTheDocument()
    expect(screen.getByText('Gruppen: board · Organisationseinheiten: HR')).toBeInTheDocument()
  })
})
