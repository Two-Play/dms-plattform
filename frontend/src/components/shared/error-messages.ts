import { ApiRequestError, UNAVAILABLE_MESSAGE } from '@/api/errors'

/** What went wrong, from the user's point of view. */
export type ErrorKind =
  | 'network'
  | 'unauthorized'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'invalid'
  | 'server'
  | 'unknown'

/** User-facing texts per kind of error; one place so they can be translated later (DMS-110). */
export const errorTexts: Record<ErrorKind, { title: string; description: string; inline: string }> =
  {
    network: {
      title: 'No connection to the server',
      description: 'Check your network connection and try again.',
      inline: 'No connection to the server. Check your network connection and try again.',
    },
    unauthorized: {
      title: 'Your session has expired',
      description: 'Sign in again to continue. Reloading the page signs you in again.',
      inline: 'Your session has expired. Reload the page to sign in again.',
    },
    forbidden: {
      title: 'You are not allowed to see this',
      description: 'Ask your administrator if you need access.',
      inline: 'You are not allowed to do this.',
    },
    notFound: {
      title: 'Not found',
      description: 'It may have been deleted, or the link is wrong.',
      inline: 'Not found. It may have been deleted in the meantime.',
    },
    conflict: {
      title: 'This was changed in the meantime',
      description: 'Reload to see the current state, then try again.',
      inline: 'This was changed in the meantime. Reload to see the current state.',
    },
    invalid: {
      title: 'The request could not be processed',
      description: 'Check the entered values and try again.',
      inline: 'The request could not be processed. Check the entered values.',
    },
    server: {
      title: 'Something went wrong on the server',
      description: 'Try again in a moment. If the problem persists, contact support.',
      inline: 'Something went wrong on the server. Try again in a moment.',
    },
    unknown: {
      title: 'Something went wrong',
      description: 'Try again. If the problem persists, contact support.',
      inline: 'Something went wrong. Try again.',
    },
  }

/** Classifies an error of an API call by HTTP status; fetch failures are network errors. */
export function errorKind(error: unknown): ErrorKind {
  if (error instanceof ApiRequestError) {
    const { status } = error
    if (status === 401) return 'unauthorized'
    if (status === 403) return 'forbidden'
    if (status === 404) return 'notFound'
    if (status === 409) return 'conflict'
    if (status === 400 || status === 422) return 'invalid'
    if (status >= 500) return 'server'
    return 'unknown'
  }
  // fetch rejects with a TypeError when the server cannot be reached.
  if (error instanceof TypeError) return 'network'
  return 'unknown'
}

/** Messages the API helpers build when the backend sent no message of its own. */
const GENERIC_MESSAGE = /failed with HTTP \d+$/

/**
 * The backend's own message, if it sent a meaningful one; generic fallbacks and technical fetch
 * errors are left out because the user-facing texts already say the same.
 */
export function errorDetail(error: unknown): string | undefined {
  if (!(error instanceof Error) || error instanceof TypeError) return undefined
  const message = error.message.trim()
  if (message === '' || GENERIC_MESSAGE.test(message) || message === UNAVAILABLE_MESSAGE) {
    return undefined
  }
  return message
}

/** Whether trying again can help; a missing page or permission does not change by retrying. */
export function isRetryable(kind: ErrorKind): boolean {
  return kind !== 'forbidden' && kind !== 'notFound' && kind !== 'invalid'
}

/** A one-line text for errors of actions (save, vote, ...): the backend's message or a friendly one. */
export function errorText(error: unknown): string {
  return errorDetail(error) ?? errorTexts[errorKind(error)].inline
}
