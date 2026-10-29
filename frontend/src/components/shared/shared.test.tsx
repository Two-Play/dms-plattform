import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '@/api/errors'
import { toast } from '@/lib/toast'
import { errorKind, errorText } from './error-messages'
import { ErrorState, InlineError } from './error-state'
import { Toaster } from './toaster'

describe('errorKind', () => {
  it.each([
    [401, 'unauthorized'],
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [422, 'invalid'],
    [400, 'invalid'],
    [500, 'server'],
    [503, 'server'],
  ] as const)('maps HTTP %i to %s', (status, kind) => {
    expect(errorKind(new ApiRequestError(status, 'x'))).toBe(kind)
  })

  it('treats a failed fetch as a network error', () => {
    expect(errorKind(new TypeError('Failed to fetch'))).toBe('network')
  })
})

describe('errorText', () => {
  it('prefers the backend message', () => {
    expect(errorText(new ApiRequestError(422, 'The title is too long.'))).toBe(
      'The title is too long.',
    )
  })

  it('replaces generic fallbacks with a friendly text', () => {
    expect(errorText(new ApiRequestError(500, 'Saving failed with HTTP 500'))).toBe(
      'Something went wrong on the server. Try again in a moment.',
    )
  })
})

describe('ErrorState', () => {
  it('explains the error and retries', async () => {
    const retry = vi.fn()
    render(
      <ErrorState
        error={new ApiRequestError(503, 'Loading failed with HTTP 503')}
        onRetry={retry}
      />,
    )
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong on the server')
    expect(alert).not.toHaveTextContent('HTTP 503')
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(retry).toHaveBeenCalled()
  })

  it('offers no retry where it cannot help and takes a custom title', () => {
    render(
      <ErrorState
        error={new ApiRequestError(404, 'document does not exist')}
        titles={{ notFound: 'This document does not exist.' }}
        onRetry={vi.fn()}
      />,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('This document does not exist.')
    expect(screen.getByText('document does not exist')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('shows a network error for a failed fetch', () => {
    render(<InlineError error={new TypeError('Failed to fetch')} />)
    expect(screen.getByRole('alert')).toHaveTextContent('No connection to the server.')
  })
})

describe('Toaster', () => {
  it('announces toasts in a live region and lets the user dismiss them', async () => {
    render(<Toaster />)
    const region = screen.getByRole('status')
    expect(region).toHaveAttribute('aria-live', 'polite')

    act(() => {
      toast('Draft saved')
    })
    expect(region).toHaveTextContent('Draft saved')

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }))
    expect(region).not.toHaveTextContent('Draft saved')
  })

  it('removes a toast after a while', () => {
    vi.useFakeTimers()
    try {
      render(<Toaster />)
      act(() => {
        toast('Vote recorded')
      })
      expect(screen.getByText('Vote recorded')).toBeInTheDocument()
      act(() => {
        vi.advanceTimersByTime(7000)
      })
      expect(screen.queryByText('Vote recorded')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})
