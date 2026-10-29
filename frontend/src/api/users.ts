import { queryOptions, useQuery, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { useApiClient } from './context'
import { requestError } from './errors'
import type { components } from './schema'

/** A user id resolved by the backend's user directory (DMS-110); no `displayName` if unknown. */
export type UserView = components['schemas']['UserView']

/** Ids per request; the backend accepts up to 200. */
const BATCH_SIZE = 100

interface Pending {
  resolve: (user: UserView) => void
  reject: (error: unknown) => void
}

/**
 * Collects the ids asked for during one tick and resolves them with one request
 * (`GET /api/v1/users?ids=…`), so a page listing many people sends one request instead of one per
 * name. DataLoader-style; one loader per API client.
 */
export class UserLoader {
  private queue = new Map<string, Pending[]>()
  private scheduled = false
  private readonly client: ApiClient

  constructor(client: ApiClient) {
    this.client = client
  }

  load(id: string): Promise<UserView> {
    return new Promise((resolve, reject) => {
      const waiting = this.queue.get(id)
      if (waiting) waiting.push({ resolve, reject })
      else this.queue.set(id, [{ resolve, reject }])
      if (!this.scheduled) {
        this.scheduled = true
        setTimeout(() => void this.flush(), 0)
      }
    })
  }

  private async flush(): Promise<void> {
    const batch = this.queue
    this.queue = new Map()
    this.scheduled = false
    const ids = [...batch.keys()]
    for (let i = 0; i < ids.length; i += BATCH_SIZE) {
      const chunk = ids.slice(i, i + BATCH_SIZE)
      try {
        const users = await this.fetch(chunk)
        const byId = new Map(users.map((u) => [u.id, u]))
        for (const id of chunk) {
          const user = byId.get(id) ?? { id }
          for (const p of batch.get(id) ?? []) p.resolve(user)
        }
      } catch (error) {
        for (const id of chunk) for (const p of batch.get(id) ?? []) p.reject(error)
      }
    }
  }

  private async fetch(ids: string[]): Promise<UserView[]> {
    const { data, error, response } = await this.client.GET('/api/v1/users', {
      params: { query: { ids: ids.join(',') } },
    })
    if (!response.ok || !data) {
      throw requestError(response, error, `Loading user names failed with HTTP ${response.status}`)
    }
    return Array.isArray(data) ? data : []
  }
}

const loaders = new WeakMap<ApiClient, UserLoader>()

function loaderFor(client: ApiClient): UserLoader {
  let loader = loaders.get(client)
  if (!loader) {
    loader = new UserLoader(client)
    loaders.set(client, loader)
  }
  return loader
}

/** The user behind `id`; undefined while loading or if the directory could not be asked. */
export function useUser(id: string | undefined) {
  const client = useApiClient()
  return useQuery({
    queryKey: ['users', id],
    queryFn: () => loaderFor(client).load(id ?? ''),
    enabled: id !== undefined && id !== '',
    // Names rarely change; the backend caches them for ten minutes as well.
    staleTime: 10 * 60_000,
    gcTime: 30 * 60_000,
    retry: false,
  })
}

/** The display name of `id` if it is in the query cache already (for toasts), else the id. */
export function cachedUserName(queryClient: QueryClient, id: string): string {
  return queryClient.getQueryData<UserView>(['users', id])?.displayName ?? id
}

/** The display name of `id` for texts such as aria-labels; the id until it is resolved. */
export function useUserLabel(id: string | undefined): string | undefined {
  const user = useUser(id)
  return user.data?.displayName ?? id
}

/** A person found by the people picker (DMS-111): the user id to store, name and email to show. */
export type UserMatch = components['schemas']['UserMatch']

/** Characters (without blanks) a search for people needs; the backend rejects shorter ones. */
export const MIN_PERSON_QUERY = 2

/** Words the backend accepts per search; more do not narrow down a person further. */
const MAX_PERSON_QUERY_WORDS = 4

/** People per search in the picker; the backend allows up to 20. */
export const PERSON_SEARCH_LIMIT = 10

/** The search text as the backend expects it, or undefined if it is too short to search yet. */
export function personQuery(text: string): string | undefined {
  const words = text.trim().split(/\s+/).filter(Boolean).slice(0, MAX_PERSON_QUERY_WORDS)
  return words.join('').length >= MIN_PERSON_QUERY ? words.join(' ') : undefined
}

/**
 * People of the caller's tenant whose name or email starts with every word of `query`
 * (`GET /api/v1/users/search`). The people found go into the name cache, so `UserName` shows the
 * picked person without another request.
 */
export function personSearchQuery(client: ApiClient, queryClient: QueryClient, query: string) {
  return queryOptions({
    queryKey: ['users', 'search', query],
    queryFn: async ({ signal }) => {
      const { data, error, response } = await client.GET('/api/v1/users/search', {
        params: { query: { q: query, limit: PERSON_SEARCH_LIMIT } },
        signal,
      })
      if (!response.ok || !data) {
        throw requestError(response, error, `Searching people failed with HTTP ${response.status}`)
      }
      const found = Array.isArray(data) ? data : []
      for (const person of found) {
        queryClient.setQueryData<UserView>(['users', person.id], person)
      }
      return found
    },
    // The backend keeps answers for a minute as well.
    staleTime: 60_000,
    retry: false,
  })
}
