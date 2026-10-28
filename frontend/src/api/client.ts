import createClient, { type Client, type Middleware } from 'openapi-fetch'
import type { paths } from './schema'

export type ApiClient = Client<paths>

/** Header carrying the OIDC ID token for step-up-protected requests (DMS-86). */
export const ID_TOKEN_HEADER = 'X-DMS-Id-Token'

/**
 * Requests that need a recent sign-in (step-up): votes on workflow tasks. ZITADEL access tokens
 * carry no `auth_time`, so the backend reads it from the ID token of the same session.
 */
export function needsIdToken(request: Request): boolean {
  return (
    request.method === 'POST' &&
    /\/api\/v1\/workflow\/tasks\/[^/]+\/votes$/.test(new URL(request.url).pathname)
  )
}

/**
 * Creates a typed client for the DMS API that sends the access token as Bearer header and, for
 * step-up-protected requests, the ID token in {@link ID_TOKEN_HEADER}.
 */
export function createApiClient(
  baseUrl: string,
  getAccessToken: () => Promise<string | undefined>,
  getIdToken: () => Promise<string | undefined> = () => Promise.resolve(undefined),
): ApiClient {
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await getAccessToken()
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      if (needsIdToken(request)) {
        const idToken = await getIdToken()
        if (idToken) request.headers.set(ID_TOKEN_HEADER, idToken)
      }
      return request
    },
  }
  const client = createClient<paths>({ baseUrl })
  client.use(auth)
  return client
}
