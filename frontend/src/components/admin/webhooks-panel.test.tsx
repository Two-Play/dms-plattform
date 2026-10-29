import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Webhook, WebhookDelivery, WebhookInput } from '@/api/webhooks'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'admin-1', name: 'Ada Admin' } },
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

interface Init {
  params?: { path?: Record<string, string>; query?: Record<string, unknown> }
  body?: unknown
}

const secret = 'whsec_c2VjcmV0LXNob3duLW9uY2UtMDEyMzQ1Njc4OWFiY2RlZg=='
const rotatedSecret = 'whsec_cm90YXRlZC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZmdoaQ=='

const intranet: Webhook = {
  id: 'w1',
  url: 'https://intranet.example.com/hooks/dms',
  eventTypes: ['document.published', 'document.withdrawn'],
  documentClasses: ['policy'],
  description: 'Intranet',
  active: true,
  createdBy: 'admin-1',
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
}

const deadDelivery: WebhookDelivery = {
  id: 'd1',
  eventId: 41,
  eventType: 'document.published',
  documentId: '7f4c1a2e-0000-4000-8000-000000000001',
  documentNumber: 'RL-042',
  status: 'dead',
  attempts: 12,
  lastStatusCode: 503,
  lastError: 'the receiver answered HTTP 503',
  createdAt: '2026-10-28T08:00:00Z',
  lastAttemptAt: '2026-10-28T12:00:00Z',
}

let webhooks: Webhook[]

beforeEach(() => {
  webhooks = [intranet]
})

afterEach(() => {
  setLocale('en')
})

function renderAdmin(options: { pingFails?: boolean } = {}) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'admin-1', tenantId: 'tenant-1', roles: ['TenantAdmin'] })
      case '/api/v1/groups':
        return ok([])
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/admin/webhooks':
        return ok(webhooks)
      case '/api/v1/admin/webhooks/{id}/deliveries':
        return ok([deadDelivery])
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: Init) => {
    switch (p) {
      case '/api/v1/admin/webhooks': {
        const body = init?.body as WebhookInput
        const created: Webhook = {
          ...intranet,
          ...body,
          id: 'w2',
          documentClasses: body.documentClasses ?? [],
          active: body.active ?? true,
        }
        webhooks = [...webhooks, created]
        return ok({ webhook: created, secret }, 201)
      }
      case '/api/v1/admin/webhooks/{id}/test':
        return ok(
          options.pingFails
            ? {
                webhookId: 'test_2',
                delivered: false,
                error: 'intranet.example.com not reachable: ConnectException',
                durationMillis: 3,
              }
            : { webhookId: 'test_1', delivered: true, statusCode: 204, durationMillis: 42 },
        )
      case '/api/v1/admin/webhooks/{id}/secret':
        return ok({ webhook: intranet, secret: rotatedSecret })
      case '/api/v1/admin/webhooks/{id}/deliveries/{deliveryId}/redeliver':
        return ok({ ...deadDelivery, status: 'pending' }, 202)
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const put = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/admin/webhooks/{id}') {
      const id = init?.params?.path?.id ?? ''
      const body = init?.body as WebhookInput
      webhooks = webhooks.map((w) =>
        w.id === id ? { ...w, ...body, active: body.active ?? true } : w,
      )
      return ok(webhooks.find((w) => w.id === id))
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const del = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/admin/webhooks/{id}') {
      webhooks = webhooks.filter((w) => w.id !== init?.params?.path?.id)
      return ok(undefined, 204)
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const client = { GET: get, POST: post, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: ['/admin'] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, put, del, user: userEvent.setup() }
}

async function openWebhooks(user: ReturnType<typeof userEvent.setup>, tab = 'Webhooks') {
  await user.click(await screen.findByRole('tab', { name: tab }, { timeout: 3000 }))
}

describe('admin: webhooks (DMS-107)', () => {
  it('lists the webhooks with a masked secret, events, classes and status', async () => {
    const { user } = renderAdmin()
    await openWebhooks(user)

    const table = await screen.findByRole('table', { name: 'Webhooks of the tenant' })
    const row = within(table).getAllByRole('row')[1] as HTMLElement
    expect(within(row).getByText('https://intranet.example.com/hooks/dms')).toBeInTheDocument()
    expect(within(row).getByText('Published, Withdrawn')).toBeInTheDocument()
    expect(within(row).getByText('policy')).toBeInTheDocument()
    expect(within(row).getByText('stored encrypted, not displayable')).toBeInTheDocument()
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(table).not.toHaveTextContent(secret)
  })

  it('creates a webhook and shows its secret only once', async () => {
    const { post, user } = renderAdmin()
    await openWebhooks(user)
    await screen.findByRole('table', { name: 'Webhooks of the tenant' })

    await user.type(screen.getByLabelText('Receiver URL *'), 'https://erp.example.com/dms')
    await user.click(screen.getByLabelText('Superseded'))
    await user.type(screen.getByLabelText('Document classes'), 'policy, contract')
    await user.type(screen.getByLabelText('Description'), 'ERP')
    await user.click(screen.getByRole('button', { name: 'Create webhook' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/admin/webhooks', {
        body: {
          url: 'https://erp.example.com/dms',
          eventTypes: ['document.published', 'document.withdrawn'],
          documentClasses: ['policy', 'contract'],
          description: 'ERP',
          active: true,
        },
      })
    })
    const notice = await screen.findByTestId('webhook-secret')
    expect(within(notice).getByText(secret)).toBeInTheDocument()
    expect(notice).toHaveTextContent(/Shown only now/)
    expect(await screen.findByText('https://erp.example.com/dms')).toBeInTheDocument()

    await user.click(within(notice).getByRole('button', { name: 'I have stored the secret' }))
    expect(screen.queryByTestId('webhook-secret')).not.toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(secret)
  })

  it('checks the URL and the events before creating', async () => {
    const { post, user } = renderAdmin()
    await openWebhooks(user)
    await screen.findByRole('table', { name: 'Webhooks of the tenant' })

    await user.type(screen.getByLabelText('Receiver URL *'), 'intranet')
    for (const name of ['Published', 'Superseded', 'Withdrawn']) {
      await user.click(screen.getByLabelText(name))
    }
    await user.click(screen.getByRole('button', { name: 'Create webhook' }))

    expect(await screen.findByText(/complete URL starting with https/)).toHaveAttribute(
      'role',
      'alert',
    )
    expect(screen.getByText('Choose at least one event.')).toHaveAttribute('role', 'alert')
    expect(post).not.toHaveBeenCalledWith('/api/v1/admin/webhooks', expect.anything())
  })

  it('sends a test event and shows the outcome', async () => {
    const { post, user } = renderAdmin()
    await openWebhooks(user)
    await user.click(
      await screen.findByRole('button', { name: 'Send a test event to intranet.example.com' }),
    )
    expect(await screen.findByTestId('test-w1')).toHaveTextContent(
      'Test event delivered to intranet.example.com: HTTP 204 in 42 ms.',
    )
    expect(post).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}/test', {
      params: { path: { id: 'w1' } },
    })
  })

  it('reports a failed test event with the reason', async () => {
    const { user } = renderAdmin({ pingFails: true })
    await openWebhooks(user)
    await user.click(
      await screen.findByRole('button', { name: 'Send a test event to intranet.example.com' }),
    )
    expect(await screen.findByTestId('test-w1')).toHaveTextContent(
      'Test event to intranet.example.com failed: intranet.example.com not reachable',
    )
  })

  it('shows the delivery log with document number, status and error, and sends again', async () => {
    const { post, user } = renderAdmin()
    await openWebhooks(user)
    await user.click(
      await screen.findByRole('button', { name: 'Show the deliveries of intranet.example.com' }),
    )
    const table = await screen.findByRole('table', {
      name: 'Latest deliveries to intranet.example.com',
    })
    const row = within(table).getAllByRole('row')[1] as HTMLElement
    expect(within(row).getByText('RL-042')).toBeInTheDocument()
    expect(within(row).getByText('Given up')).toBeInTheDocument()
    expect(within(row).getByText('12 attempts')).toBeInTheDocument()
    expect(within(row).getByText('HTTP 503')).toBeInTheDocument()
    expect(within(row).getByText('the receiver answered HTTP 503')).toBeInTheDocument()

    await user.click(within(row).getByRole('button', { name: /Send delivery of .* again/ }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith(
        '/api/v1/admin/webhooks/{id}/deliveries/{deliveryId}/redeliver',
        { params: { path: { id: 'w1', deliveryId: 'd1' } } },
      )
    })
  })

  it('pauses, edits, rotates the secret after confirmation and deletes', async () => {
    const { put, post, del, user } = renderAdmin()
    await openWebhooks(user)

    await user.click(
      await screen.findByRole('button', { name: 'Pause webhook intranet.example.com' }),
    )
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}', {
        params: { path: { id: 'w1' } },
        body: {
          url: 'https://intranet.example.com/hooks/dms',
          eventTypes: ['document.published', 'document.withdrawn'],
          documentClasses: ['policy'],
          description: 'Intranet',
          active: false,
        },
      })
    })
    expect(await screen.findByText('Paused')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Edit webhook intranet.example.com' }))
    const editor = screen.getByRole('region', { name: 'Edit webhook intranet.example.com' })
    const description = within(editor).getByLabelText('Description')
    await user.clear(description)
    await user.type(description, 'Intranet (new)')
    await user.click(within(editor).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => {
      expect(put).toHaveBeenLastCalledWith('/api/v1/admin/webhooks/{id}', {
        params: { path: { id: 'w1' } },
        body: expect.objectContaining({ description: 'Intranet (new)', active: false }) as unknown,
      })
    })

    await user.click(
      screen.getByRole('button', { name: 'Rotate the secret of intranet.example.com' }),
    )
    const rotate = await screen.findByRole('dialog')
    expect(rotate).toHaveTextContent(/receiver rejects them until it has the new secret/)
    await user.click(within(rotate).getByRole('button', { name: 'Rotate secret' }))
    expect(await screen.findByText(rotatedSecret)).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}/secret', {
      params: { path: { id: 'w1' } },
    })

    await user.click(screen.getByRole('button', { name: 'Delete webhook intranet.example.com' }))
    const remove = await screen.findByRole('dialog')
    expect(remove).toHaveTextContent(/delivery log is kept/)
    await user.click(within(remove).getByRole('button', { name: 'Delete webhook' }))
    await waitFor(() => {
      expect(del).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}', {
        params: { path: { id: 'w1' } },
      })
    })
    expect(await screen.findByText('No webhooks yet.')).toBeInTheDocument()
  })

  it('shows the tab in German', async () => {
    setLocale('de')
    const { user } = renderAdmin()
    await openWebhooks(user, 'Webhooks')

    const table = await screen.findByRole('table', { name: 'Webhooks des Mandanten' })
    expect(within(table).getByText('Veröffentlicht, Zurückgezogen')).toBeInTheDocument()
    expect(
      within(table).getByText('verschlüsselt gespeichert, nicht anzeigbar'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Webhook anlegen' })).toBeInTheDocument()
    await user.click(
      screen.getByRole('button', { name: 'Test-Ping an intranet.example.com senden' }),
    )
    expect(await screen.findByTestId('test-w1')).toHaveTextContent(
      'Test-Ping an intranet.example.com zugestellt: HTTP 204 in 42 ms.',
    )
  })
})
