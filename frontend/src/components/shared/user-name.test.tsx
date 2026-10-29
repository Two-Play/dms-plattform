import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { UserName, UserNames } from './user-name'

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const directory: Record<string, { displayName?: string; email?: string }> = {
  'u-1': { displayName: 'Anna Author', email: 'anna@example.com' },
  'u-2': { displayName: 'Rita Reviewer' },
}

function renderWith(get: (path: string, init?: unknown) => Reply, ui: React.ReactNode) {
  const client = { GET: vi.fn(get) } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{ui}</ApiClientProvider>
    </QueryClientProvider>,
  )
  return client.GET as unknown as ReturnType<typeof vi.fn>
}

const users = (path: string, init?: unknown): Reply => {
  if (path !== '/api/v1/users') throw new Error(`unexpected ${path}`)
  const ids = (init as { params: { query: { ids: string } } }).params.query.ids.split(',')
  return Promise.resolve({
    data: ids.map((id) => ({ id, ...directory[id] })),
    error: undefined,
    response: new Response(null, { status: 200 }),
  })
}

describe('UserName (DMS-110)', () => {
  it('shows display names with the id as tooltip, asking for all names on a page at once', async () => {
    const get = renderWith(
      users,
      <p>
        <UserNames ids={['u-1', 'u-2', 'unknown-9']} /> and <UserName id="u-1" />
      </p>,
    )

    expect(await screen.findAllByText('Anna Author')).toHaveLength(2)
    expect(screen.getByText('Rita Reviewer')).toHaveAttribute('title', 'u-2')
    // Unknown in the tenant: the id stays.
    expect(screen.getByText('unknown-9')).toHaveAttribute('title', 'unknown-9')
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/api/v1/users', {
      params: { query: { ids: 'u-1,u-2,unknown-9' } },
    })
  })

  it('falls back to the id when the user directory is unavailable', async () => {
    const get = renderWith(
      () =>
        Promise.resolve({
          data: undefined,
          error: { code: 'user_directory_unavailable', message: 'down' },
          response: new Response(null, { status: 503 }),
        }),
      <UserName id="u-1" />,
    )
    await vi.waitFor(() => {
      expect(get).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByText('u-1')).toHaveAttribute('title', 'u-1')
  })
})
