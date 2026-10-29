import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { LegalHoldRef } from '@/api/legal-holds'
import { setLocale } from '@/i18n'
import { LegalHoldBadge } from './legal-hold-badge'

function renderBadge(holds?: LegalHoldRef[]) {
  const rootRoute = createRootRoute({ component: () => <LegalHoldBadge holds={holds} /> })
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  render(<RouterProvider router={router} />)
  return userEvent.setup()
}

const holds: LegalHoldRef[] = [
  {
    id: 'hold-1',
    name: 'Litigation Müller GmbH',
    matterReference: 'LIT-2026-7',
    createdAt: '2026-10-20T09:00:00Z',
  },
]

describe('legal hold badge', () => {
  it('tells every reader that the document is held, without any hold details', async () => {
    renderBadge()
    const badge = await screen.findByTestId('legal-hold-badge')
    expect(badge).toHaveTextContent('Legal Hold')
    expect(badge).toHaveTextContent('The document is under legal hold and cannot be destroyed.')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/Müller/)).not.toBeInTheDocument()
  })

  it('shows the names of the holds to callers who may manage them', async () => {
    const user = renderBadge(holds)
    const trigger = await screen.findByRole('button', {
      name: 'Legal Hold: 1 active hold, show it',
    })
    expect(screen.queryByText('Litigation Müller GmbH')).not.toBeInTheDocument()
    await user.click(trigger)
    const list = await screen.findByRole('list', { name: 'Active legal holds' })
    expect(within(list).getByRole('link', { name: 'Litigation Müller GmbH' })).toHaveAttribute(
      'href',
      '/legal-holds?hold=hold-1',
    )
    expect(within(list).getByText(/^LIT-2026-7, since/)).toBeInTheDocument()
  })

  it('is labelled in German', async () => {
    setLocale('de')
    renderBadge()
    expect(await screen.findByTestId('legal-hold-badge')).toHaveTextContent(
      'Das Dokument steht unter Legal Hold und kann nicht vernichtet werden.',
    )
  })
})
