import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError, UserFacingError } from '@/api/errors'
import { setLocale } from '@/i18n'
import { toast } from '@/lib/toast'
import { errorDetail, errorKind, errorText, issueText } from './error-messages'
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
  it('never shows the English message of the backend', () => {
    const error = new ApiRequestError(422, 'The title is too long.')
    expect(errorText(error)).toBe('The request could not be processed. Check the entered values.')
    expect(errorDetail(error)).toBe('The title is too long.')
  })

  it.each([
    ['segregation_of_duties', 'Vier-Augen-Prinzip'],
    ['unauthorized_deviations', 'Abweichungsantrag'],
    ['office_file_too_complex', 'zu groß oder zu komplex'],
    ['pdf_limit_reached', 'zu viele PDFs'],
    ['search_unavailable', 'Suche'],
    ['notice_too_soon', '24 Stunden'],
    ['report_removed', 'Aufbewahrung'],
  ])('translates the code %s', (code, text) => {
    setLocale('de')
    expect(errorText(new ApiRequestError(422, 'server text', code))).toContain(text)
  })

  it('says a body-less 502 or 503 is a server problem, without naming the partner master', () => {
    setLocale('de')
    const error = new ApiRequestError(503, 'Casting the vote failed with HTTP 503')
    expect(errorText(error)).toBe(
      'Auf dem Server ist ein Fehler aufgetreten. Bitte gleich erneut versuchen.',
    )
  })

  it('keeps a message the client wrote for the user', () => {
    expect(errorText(new UserFacingError('Choose a class.'))).toBe('Choose a class.')
    expect(errorDetail(new UserFacingError('Choose a class.'))).toBeUndefined()
  })

  it('replaces generic fallbacks with a friendly text', () => {
    expect(errorText(new ApiRequestError(500, 'Saving failed with HTTP 500'))).toBe(
      'Something went wrong on the server. Try again in a moment.',
    )
  })
})

describe('issueText', () => {
  it('translates the issues of a new version and keeps what it does not know', () => {
    setLocale('de')
    expect(issueText({ code: 'confidentiality_lowered', message: 'x' })).toContain('vertraulich')
    expect(issueText({ code: 'access_groups_widened', message: 'x' })).toContain('Eigentümer')
    expect(issueText({ code: 'out_of_range', message: 'at most 5' })).toBe('at most 5')
  })
})

describe('InlineError', () => {
  it('keeps the message of the backend in collapsed technical details', () => {
    setLocale('de')
    render(<InlineError error={new ApiRequestError(409, 'version 2 is InReview')} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Inzwischen geändert')
    expect(screen.getByText('version 2 is InReview')).not.toBeVisible()
    expect(screen.getByText('Technische Details')).toBeVisible()
  })

  it('takes the text of the caller', () => {
    render(<InlineError error={new ApiRequestError(500, 'x failed with HTTP 500')} text="Mine" />)
    expect(screen.getByRole('alert')).toHaveTextContent(/^Mine$/)
  })
})

describe('ErrorState', () => {
  it('does not add the reason phrase of a refused request', () => {
    setLocale('de')
    render(<ErrorState error={new ApiRequestError(403, 'Forbidden')} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Keine Berechtigung')
    expect(alert).not.toHaveTextContent('Forbidden')
    expect(screen.queryByText('Technische Details')).not.toBeInTheDocument()
  })

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
    expect(screen.getByText('document does not exist')).not.toBeVisible()
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
