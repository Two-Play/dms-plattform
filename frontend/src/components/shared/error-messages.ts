import { ApiRequestError, UserFacingError, type FieldIssue } from '@/api/errors'
import { t, tOptional } from '@/i18n'

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

/** Reason phrases of a proxy or framework: the user-facing texts already say the same. */
const REASON_PHRASE =
  /^(bad request|unauthorized|forbidden|not found|conflict|unprocessable entity|too many requests|internal server error|bad gateway|service unavailable|gateway timeout)\.?$/i

/**
 * What the backend said, for the collapsed technical details. Its messages are English and written for
 * support: `errorText` is what the user reads. Generic fallbacks, reason phrases and technical fetch
 * errors are left out because they add nothing.
 */
export function errorDetail(error: unknown): string | undefined {
  if (!(error instanceof Error) || error instanceof TypeError || error instanceof UserFacingError) {
    return undefined
  }
  const message = error.message.trim()
  if (message === '' || GENERIC_MESSAGE.test(message) || REASON_PHRASE.test(message)) {
    return undefined
  }
  return message
}

/** Whether trying again can help; a missing page or permission does not change by retrying. */
export function isRetryable(kind: ErrorKind): boolean {
  return kind !== 'forbidden' && kind !== 'notFound' && kind !== 'invalid'
}

/** The text for an error code of the backend, if the catalogs know the code. */
export function codeText(error: unknown): string | undefined {
  return error instanceof ApiRequestError && error.code !== undefined
    ? tOptional(`errors.codes.${error.code}`)
    : undefined
}

/** A one-line text for errors of actions (save, vote, ...), in the language of the user. */
export function errorText(error: unknown): string {
  if (error instanceof UserFacingError) return error.message
  return codeText(error) ?? errorTexts(errorKind(error)).inline
}

/** What is wrong with a field, in the language of the user if the catalogs know the issue. */
export function issueText(issue: Pick<FieldIssue, 'code' | 'message'>): string {
  return tOptional(`errors.issues.${issue.code}`) ?? issue.message
}
