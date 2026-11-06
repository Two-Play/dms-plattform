import { QueryClient } from '@tanstack/react-query'
import { ApiRequestError } from './errors'

/** One more try, except for a missing permission or page: the same call gets the same answer. */
export function retryQuery(failureCount: number, error: Error): boolean {
  if (error instanceof ApiRequestError && (error.status === 403 || error.status === 404)) {
    return false
  }
  return failureCount < 1
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { staleTime: 10_000, retry: retryQuery } },
  })
}
