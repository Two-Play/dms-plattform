import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type {
  AcknowledgementSettings,
  AcknowledgementSettingsOverview,
} from '@/api/acknowledgement-overview'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { setLocale } from '@/i18n'
import { getToasts } from '@/lib/toast'
import { AcknowledgementSettingsPanel } from './acknowledgement-settings-panel'

const policySettings: AcknowledgementSettings = {
  roles: [],
  groups: ['board'],
  units: ['HR'],
  reminderDays: 7,
  updatedAt: '2026-10-28T09:00:00Z',
  updatedBy: 'admin-1',
}

const settings: AcknowledgementSettingsOverview = {
  defaultReminderDays: 14,
  roles: ['Reader', 'Author', 'Owner'],
  classes: [
    {
      classId: 'c1',
      classKey: 'policy',
      label: 'Policy',
      settings: policySettings,
    },
    { classId: 'c2', classKey: 'hr-policy', label: 'HR policy', parentId: 'c1' },
  ],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

function renderPanel(save: () => Reply = () => ok(policySettings)) {
  const get = vi.fn((path: string) =>
    path === '/api/v1/groups'
      ? ok([
          { key: 'board', name: 'Board', createdBy: 'admin-1', createdAt: '2026-10-01T08:00:00Z' },
        ])
      : ok(settings),
  )
  const put = vi.fn(save)
  const del = vi.fn(() => ok(undefined, 204))
  const client = { GET: get, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <AcknowledgementSettingsPanel />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, put, del, user: userEvent.setup() }
}

describe('Acknowledgement settings per class (DMS-100)', () => {
  it('lists the tenant default and every class with own or inherited settings', async () => {
    renderPanel()
    const table = await screen.findByRole('table')
    const policy = within(table).getByRole('row', { name: /^Policy/ })
    expect(within(policy).getByText('board, HR')).toBeInTheDocument()
    expect(within(policy).getByText('7 days')).toBeInTheDocument()
    const child = within(table).getByRole('row', { name: /^HR policy/ })
    expect(within(child).getAllByText('inherited')).toHaveLength(2)
    const tenant = within(table).getByRole('row', { name: /^Tenant default/ })
    expect(within(tenant).getByText('every reader')).toBeInTheDocument()
    expect(within(tenant).getByText('installation default (14)')).toBeInTheDocument()
  })

  it('saves roles, groups, units and the reminder interval of a class', async () => {
    const { put, user } = renderPanel()
    await user.click(await screen.findByRole('button', { name: 'Edit the settings of HR policy' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Author' }))
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Board' }))
    await user.type(within(dialog).getByLabelText('Organizational units'), 'HR, Finance')
    await user.type(within(dialog).getByLabelText('Reminder interval in days'), '500')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      'Enter a whole number from 0 to 365, or leave it empty.',
    )
    expect(put).not.toHaveBeenCalled()

    await user.clear(within(dialog).getByLabelText('Reminder interval in days'))
    await user.type(within(dialog).getByLabelText('Reminder interval in days'), '0')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(put).toHaveBeenCalledWith('/api/v1/admin/acknowledgement-settings/classes/{classId}', {
      params: { path: { classId: 'c2' } },
      body: { roles: ['Author'], groups: ['board'], units: ['HR', 'Finance'], reminderDays: 0 },
    })
    await waitFor(() => {
      expect(getToasts().map((t) => t.message)).toContain('Settings for HR policy saved.')
    })
  })

  it('shows the backend error when saving fails', async () => {
    const { user } = renderPanel(() =>
      failed(422, { code: 'invalid', message: "access group 'gone' does not exist" }),
    )
    await user.click(await screen.findByRole('button', { name: 'Edit the settings of Policy' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
  })

  it('removes the settings of a class so it inherits again', async () => {
    const { del, user } = renderPanel()
    await user.click(
      await screen.findByRole('button', {
        name: 'Remove the settings of Policy so it inherits again',
      }),
    )
    expect(del).toHaveBeenCalledWith('/api/v1/admin/acknowledgement-settings/classes/{classId}', {
      params: { path: { classId: 'c1' } },
    })
    await waitFor(() => {
      expect(getToasts().map((t) => t.message)).toContain('Settings for Policy removed.')
    })
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel()
    expect(await screen.findByText('Kenntnisnahme je Dokumentklasse')).toBeInTheDocument()
    expect(await screen.findByRole('row', { name: /^Mandantenvorgabe/ })).toBeInTheDocument()
    expect(screen.getByText('7 Tage')).toBeInTheDocument()
    expect(screen.getAllByText('geerbt')).toHaveLength(2)
  })
})
