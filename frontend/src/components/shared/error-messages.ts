import { ApiRequestError, UNAVAILABLE_MESSAGE } from '@/api/errors'
import { t } from '@/i18n'

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

/** User-facing texts per kind of error, in the active language (`errors.*` in the message catalogs). */
export function errorTexts(kind: ErrorKind): {
  title: string
  description: string
  inline: string
} {
  return {
    title: t(`errors.${kind}.title`),
    description: t(`errors.${kind}.description`),
    inline: t(`errors.${kind}.inline`),
  }
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
  return errorDetail(error) ?? errorTexts(errorKind(error)).inline
}
