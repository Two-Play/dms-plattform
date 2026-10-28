import type { components } from './schema'

export type ApiErrorBody = components['schemas']['ApiError']
export type FieldIssue = components['schemas']['IssueView']

/** Shown when the partner master cannot answer and the backend sent no message. */
export const UNAVAILABLE_MESSAGE =
  'The partner master is not reachable right now. You can save the draft without the partner and pick it later.'

/** A failed API call with the backend's error code, message and field issues, if it sent them. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly issues: FieldIssue[]

  constructor(status: number, message: string, code?: string, issues: FieldIssue[] = []) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
    this.issues = issues
  }

  /** The partner master is down or answered with something unusable (503/502). */
  get partnerDirectoryUnavailable(): boolean {
    return (
      this.code === 'partner_directory_unavailable' ||
      this.code === 'partner_directory_invalid_response'
    )
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

/** The error of a failed call, with the backend's code, message and issues if it sent an `ApiError`. */
export function requestError(response: Response, raw: unknown, fallback: string): ApiRequestError {
  const body = errorBody(raw)
  if (response.status === 502 || response.status === 503) {
    return new ApiRequestError(
      response.status,
      body?.message ?? UNAVAILABLE_MESSAGE,
      body?.code ?? 'partner_directory_unavailable',
    )
  }
  return new ApiRequestError(response.status, body?.message ?? fallback, body?.code, body?.issues)
}
