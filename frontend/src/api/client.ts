import createClient, { type Client, type Middleware } from 'openapi-fetch'
import type { paths } from './schema'

export type ApiClient = Client<paths>

/** Creates a typed client for the DMS API that sends the access token as Bearer header. */
export function createApiClient(
  baseUrl: string,
  getAccessToken: () => Promise<string | undefined>,
): ApiClient {
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await getAccessToken()
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      return request
    },
  }
  const client = createClient<paths>({ baseUrl })
  client.use(auth)
  return client
}
