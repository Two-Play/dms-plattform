import type { components } from './schema'

export type ApiErrorBody = components['schemas']['ApiError']
export type FieldIssue = components['schemas']['IssueView']

/**
 * An error whose message is already written for the user, in their language: a check of the client
 * inside an action, not an answer of the backend.
 */
export class UserFacingError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UserFacingError'
  }
}

/**
 * A failed API call with the backend's error code, message and field issues, if it sent them, and the
 * seconds to wait from `Retry-After`, if the backend asked the caller to come back later.
 */
export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly issues: FieldIssue[]
  readonly retryAfterSeconds: number | undefined

  constructor(
    status: number,
    message: string,
    code?: string,
    issues: FieldIssue[] = [],
    retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
    this.issues = issues
    this.retryAfterSeconds = retryAfterSeconds
  }
}

function isIssue(value: unknown): value is FieldIssue {
  if (typeof value !== 'object' || value === null) return false
  const { field, label, code, message } = value as Record<string, unknown>
  return (
    typeof field === 'string' &&
    typeof label === 'string' &&
    typeof code === 'string' &&
    typeof message === 'string'
  )
}

function errorBody(body: unknown): ApiErrorBody | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { code, message, issues } = body as Record<string, unknown>
  if (typeof code !== 'string' || typeof message !== 'string') return undefined
  return { code, message, issues: Array.isArray(issues) ? issues.filter(isIssue) : undefined }
}

/** `Retry-After` in seconds; the backend never sends the date form. */
function retryAfter(response: Response): number | undefined {
  const value = response.headers.get('Retry-After')
  return value !== null && /^\d+$/.test(value) ? Number(value) : undefined
}

/** The error of a failed call, with the backend's code, message and issues if it sent an `ApiError`. */
export function requestError(response: Response, raw: unknown, fallback: string): ApiRequestError {
  const body = errorBody(raw)
  return new ApiRequestError(
    response.status,
    body?.message ?? fallback,
    body?.code,
    body?.issues,
    retryAfter(response),
  )
}

/**
 * The error of a call to a partner endpoint. Only there does a 502 or 503 without an `ApiError`, from a
 * proxy in front of the backend, mean that the partner master is out of reach.
 */
export function partnerRequestError(
  response: Response,
  raw: unknown,
  fallback: string,
): ApiRequestError {
  const error = requestError(response, raw, fallback)
  return (response.status === 502 || response.status === 503) && error.code === undefined
    ? new ApiRequestError(response.status, fallback, 'partner_directory_unavailable')
    : error
}
