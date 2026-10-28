import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export { ApiRequestError, UNAVAILABLE_MESSAGE } from './errors'

export type Partner = components['schemas']['PartnerView']
export type ReferenceField = components['schemas']['ReferenceFieldView']
export type DocumentView = components['schemas']['DocumentView']
export type DocumentVersionView = components['schemas']['DocumentVersionView']

/** Shortest search text the backend accepts. */
export const MIN_QUERY_LENGTH = 2

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
