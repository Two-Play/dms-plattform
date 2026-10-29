import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError } from './errors'

export function healthQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['health'],
    queryFn: async () => {
      const { data, response } = await client.GET('/health')
      if (!response.ok || !data) {
        throw new ApiRequestError(
          response.status,
          `Health check failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    refetchInterval: 30_000,
  })
}
