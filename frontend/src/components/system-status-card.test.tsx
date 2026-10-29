import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { SystemStatusCard } from './system-status-card'

function renderWithClient(get: ReturnType<typeof vi.fn>) {
  const client = { GET: get } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <SystemStatusCard />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
}

describe('SystemStatusCard', () => {
  it('shows status and version from GET /health', async () => {
    const get = vi.fn().mockResolvedValue({
      data: { status: 'ok', version: '1.2.3' },
      response: new Response(null, { status: 200 }),
    })

    renderWithClient(get)

    expect(await screen.findByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText('ok')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/health')
  })

  it('shows an error when the backend is unavailable', async () => {
    const get = vi.fn().mockResolvedValue({
      data: undefined,
      response: new Response(null, { status: 502 }),
    })

    renderWithClient(get)

    expect(await screen.findByRole('alert')).toHaveTextContent('The backend is unavailable')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
