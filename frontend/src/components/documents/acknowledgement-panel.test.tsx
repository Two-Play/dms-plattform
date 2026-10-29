import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AcknowledgementState } from '@/api/acknowledgements'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentVersionView } from '@/api/partners'
import { setLocale } from '@/i18n'
import { AcknowledgementPanel } from './acknowledgement-panel'

const documentId = 'd1'
const version = {
  id: 'v1',
  version: '2.0',
  status: 'Published',
  requiresAcknowledgement: true,
} as unknown as DocumentVersionView

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })

function renderPanel(state: AcknowledgementState) {
  const client = {
    GET: vi.fn(() => ok(state)),
    POST: vi.fn(() => ok(undefined, 204)),
  } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <AcknowledgementPanel documentId={documentId} version={version} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
}

describe('Acknowledgement panel for readers outside the recipients (DMS-100)', () => {
  it('says that the version does not ask the reader and offers no button', async () => {
    renderPanel({ versionId: 'v1', versionLabel: '2.0', requested: false, opened: false })
    expect(
      await screen.findByText('This version does not ask you for a read confirmation.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
  })

  it('asks recipients to confirm', async () => {
    renderPanel({ versionId: 'v1', versionLabel: '2.0', requested: true, opened: true })
    expect(await screen.findByRole('button', { name: 'Acknowledge' })).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    renderPanel({ versionId: 'v1', versionLabel: '2.0', requested: false, opened: false })
    expect(
      await screen.findByText('Diese Version verlangt von Ihnen keine Kenntnisnahme.'),
    ).toBeInTheDocument()
  })
})
