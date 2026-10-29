import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { setLocale } from '@/i18n'
import { DocumentNumberAction } from './document-number-action'

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface Init {
  params?: { path?: Record<string, string> }
  body?: { number: string; reason: string }
}

function renderAction(props: { documentNumber?: string; canAssign: boolean }) {
  const post = vi.fn((_: string, init?: Init) =>
    init?.body?.number === 'VTR-TAKEN'
      ? failed(409, { code: 'conflict', message: 'the number VTR-TAKEN is already assigned' })
      : ok({
          documentId: 'd1',
          documentNumber: init?.body?.number,
          previous: props.documentNumber,
        }),
  )
  const client = { POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <DocumentNumberAction documentId="d1" {...props} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { post, invalidate, user: userEvent.setup() }
}

describe('document number on the document page (DMS-113)', () => {
  it('shows nothing without number and permission', () => {
    renderAction({ canAssign: false })
    expect(screen.queryByTestId('document-number')).not.toBeInTheDocument()
  })

  it('shows the number without the action for callers who may not assign', () => {
    renderAction({ documentNumber: 'VTR-2026-0001', canAssign: false })
    expect(screen.getByText('No. VTR-2026-0001')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('assigns a number with a reason after checking both', async () => {
    const { post, invalidate, user } = renderAction({ canAssign: true })
    await user.click(screen.getByRole('button', { name: 'Assign number' }))
    expect(screen.getByRole('dialog', { name: 'Assign document number' })).toBeInTheDocument()

    await user.type(screen.getByLabelText('Document number *'), 'VTR/1')
    await user.click(screen.getByRole('button', { name: 'Assign number' }))
    expect(screen.getByText(/starting with a letter or digit/)).toHaveAttribute('role', 'alert')
    expect(screen.getByText('Enter the reason.')).toHaveAttribute('role', 'alert')
    expect(post).not.toHaveBeenCalled()

    await user.clear(screen.getByLabelText('Document number *'))
    await user.type(screen.getByLabelText('Document number *'), ' VTR-2019-0042 ')
    await user.type(screen.getByLabelText('Reason *'), 'Taken over from the paper register')
    await user.click(screen.getByRole('button', { name: 'Assign number' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/number', {
        params: { path: { id: 'd1' } },
        body: { number: 'VTR-2019-0042', reason: 'Taken over from the paper register' },
      })
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['documents'] })
  })

  it('shows the conflict of a number that is taken', async () => {
    const { user } = renderAction({ documentNumber: 'VTR-2026-0001', canAssign: true })
    await user.click(screen.getByRole('button', { name: 'Change number' }))
    const number = screen.getByLabelText('Document number *')
    expect(number).toHaveValue('VTR-2026-0001')
    await user.clear(number)
    await user.type(number, 'VTR-TAKEN')
    await user.type(screen.getByLabelText('Reason *'), 'duplicate')
    await user.click(screen.getByRole('button', { name: 'Assign number' }))
    expect(await screen.findByText(/already assigned/)).toHaveAttribute('role', 'alert')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('speaks German', async () => {
    setLocale('de')
    const { user } = renderAction({ documentNumber: 'RL-007', canAssign: true })
    expect(screen.getByText('Nr. RL-007')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Nummer ändern' }))
    expect(screen.getByRole('dialog', { name: 'Dokumentnummer vergeben' })).toBeInTheDocument()
    expect(screen.getByLabelText('Begründung *')).toBeInTheDocument()
  })
})
