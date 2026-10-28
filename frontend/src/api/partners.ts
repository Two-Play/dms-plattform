import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import type { components } from './schema'

export type Partner = components['schemas']['PartnerView']
export type ReferenceField = components['schemas']['ReferenceFieldView']
export type ApiErrorBody = components['schemas']['ApiError']
export type DocumentView = components['schemas']['DocumentView']
export type DocumentVersionView = components['schemas']['DocumentVersionView']

/** Shortest search text the backend accepts. */
export const MIN_QUERY_LENGTH = 2

/** Shown when the partner master cannot answer and the backend sent no message. */
export const UNAVAILABLE_MESSAGE =
  'The partner master is not reachable right now. You can save the draft without the partner and pick it later.'

/** A failed API call with the backend's error code and message, if it sent one. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly code: string | undefined

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiRequestError'
    this.status = status
    this.code = code
  }

  /** The partner master is down or answered with something unusable (503/502). */
  get partnerDirectoryUnavailable(): boolean {
    return (
      this.code === 'partner_directory_unavailable' ||
      this.code === 'partner_directory_invalid_response'
    )
  }
}

function errorBody(body: unknown): ApiErrorBody | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { code, message } = body as Record<string, unknown>
  return typeof code === 'string' && typeof message === 'string' ? { code, message } : undefined
}

/** The error of a failed call, with the backend's code and message if it sent an `ApiError`. */
export function requestError(response: Response, raw: unknown, fallback: string): ApiRequestError {
  const body = errorBody(raw)
  if (response.status === 502 || response.status === 503) {
    return new ApiRequestError(
      response.status,
      body?.message ?? UNAVAILABLE_MESSAGE,
      body?.code ?? 'partner_directory_unavailable',
    )
  }
  return new ApiRequestError(response.status, body?.message ?? fallback, body?.code)
}

/** Partners whose name or identifier contains `query` (GET /api/v1/partners). */
export function partnerSearchQuery(client: ApiClient, query: string) {
  const q = query.trim()
  return queryOptions({
    queryKey: ['partners', 'search', q],
    queryFn: async ({ signal }) => {
      const { data, error, response } = await client.GET('/api/v1/partners', {
        params: { query: { q, limit: 10 } },
        signal,
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Searching partners failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    enabled: q.length >= MIN_QUERY_LENGTH,
    retry: false,
    staleTime: 30_000,
  })
}

/** The value of a partner attribute as the backend takes it over (see `Partner.attribute`). */
export function partnerAttribute(partner: Partner, attribute: string): string {
  switch (attribute) {
    case 'id':
      return partner.id
    case 'name':
      return partner.name
    case 'street':
      return partner.street ?? ''
    case 'postalCode':
      return partner.postalCode ?? ''
    case 'city':
      return partner.city ?? ''
    case 'country':
      return partner.country ?? ''
    case 'address':
      return partner.address
    case 'vatId':
      return partner.vatId ?? ''
    default:
      return ''
  }
}
