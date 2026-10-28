import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClient } from './client'

describe('createApiClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the access token as Bearer header', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(Response.json({ status: 'ok', version: '1' }))
    vi.stubGlobal('fetch', fetchMock)

    const client = createApiClient('http://api.test', () => Promise.resolve('token-123'))
    const { data } = await client.GET('/health')

    expect(data).toEqual({ status: 'ok', version: '1' })
    const request = fetchMock.mock.calls[0]?.[0] as Request
    expect(request.url).toBe('http://api.test/health')
    expect(request.headers.get('Authorization')).toBe('Bearer token-123')
  })
})
