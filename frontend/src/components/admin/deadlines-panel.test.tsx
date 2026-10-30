import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DeadlinePolicy, DeadlineSettings } from '@/api/workflow'
import { setLocale } from '@/i18n'
import { DeadlinesPanel } from './deadlines-panel'

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

const installation: DeadlinePolicy = {
  dueWorkingDays: 5,
  reminderWorkingDays: 1,
  escalationWorkingDays: 2,
  escalateTo: ['Owner'],
}

function renderPanel(initial: DeadlineSettings) {
  let current = initial
  const get = vi.fn(() => ok(current))
  const put = vi.fn((_path: string, init: { body: DeadlinePolicy }) => {
    current = {
      source: 'tenant',
      deadlines: init.body,
      installation,
      updatedAt: '2026-10-30T10:00:00Z',
      updatedBy: 'admin-1',
    }
    return ok(current)
  })
  const del = vi.fn(() => {
    current = { source: 'installation', deadlines: installation, installation }
    return ok(current)
  })
  const client = { GET: get, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <DeadlinesPanel />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, put, del, user: userEvent.setup() }
}

describe('deadlines panel (DMS-87)', () => {
  it('shows the platform default and saves the organization’s deadlines', async () => {
    const { put, user } = renderPanel({
      source: 'installation',
      deadlines: installation,
      installation,
    })
    expect(
      await screen.findByText(
        'The platform default applies: due after 5 working days, reminder 1 before, escalation 2 after.',
      ),
    ).toBeInTheDocument()
    const due = screen.getByLabelText('Due after (working days)')
    expect(due).toHaveValue('5')
    expect(screen.getByRole('checkbox', { name: 'Owner of the document' })).toBeChecked()
    expect(screen.queryByRole('button', { name: 'Use the platform default' })).toBeNull()

    await user.clear(due)
    await user.type(due, '3')
    await user.click(screen.getByRole('checkbox', { name: 'Tenant administrator' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledTimes(1)
    })
    expect(put.mock.calls[0]?.[1]).toEqual({
      body: {
        dueWorkingDays: 3,
        reminderWorkingDays: 1,
        escalationWorkingDays: 2,
        escalateTo: ['Owner', 'TenantAdmin'],
      },
    })
    expect(await screen.findByText('Your organization sets these deadlines.')).toBeInTheDocument()
  })

  it('rejects a reminder that is not before the deadline and invalid numbers', async () => {
    const { put, user } = renderPanel({
      source: 'installation',
      deadlines: installation,
      installation,
    })
    const reminder = await screen.findByLabelText('Reminder before (working days)')
    await user.clear(reminder)
    await user.type(reminder, '5')
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The reminder must come before the deadline.',
    )
    const escalation = screen.getByLabelText('Escalation after (working days)')
    await user.clear(escalation)
    await user.type(escalation, 'x')
    expect(escalation).toHaveAttribute('aria-invalid', 'true')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(put).not.toHaveBeenCalled()
  })

  it('goes back to the platform default', async () => {
    const { del, user } = renderPanel({
      source: 'tenant',
      deadlines: { ...installation, dueWorkingDays: 10 },
      installation,
      updatedAt: '2026-10-30T10:00:00Z',
      updatedBy: 'admin-1',
    })
    await user.click(await screen.findByRole('button', { name: 'Use the platform default' }))
    await waitFor(() => {
      expect(del).toHaveBeenCalledTimes(1)
    })
    expect(await screen.findByLabelText('Due after (working days)')).toHaveValue('5')
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel({ source: 'installation', deadlines: installation, installation })
    expect(await screen.findByText('Fristen für Prüfungen und Freigaben')).toBeInTheDocument()
    expect(await screen.findByLabelText('Fällig nach (Arbeitstage)')).toHaveValue('5')
    expect(screen.getByRole('checkbox', { name: 'Eigentümer des Dokuments' })).toBeChecked()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeInTheDocument()
  })
})
