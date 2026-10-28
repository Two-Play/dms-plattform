import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApiClient, ID_TOKEN_HEADER } from './client'
import { castVote } from './workflow'

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

  it('sends the ID token only with step-up-protected requests (votes)', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(() => Promise.resolve(Response.json({ status: 'ok', version: '1' })))
    vi.stubGlobal('fetch', fetchMock)
    const getIdToken = vi.fn(() => Promise.resolve('id-token-1'))

    const client = createApiClient(
      'http://api.test',
      () => Promise.resolve('token-123'),
      getIdToken,
    )
    await client.GET('/health')
    await client.POST('/api/v1/workflow/tasks/{taskId}/votes', {
      params: { path: { taskId: 'task-1' } },
      body: { decision: 'Approve' },
    })

    const health = fetchMock.mock.calls[0]?.[0] as Request
    const vote = fetchMock.mock.calls[1]?.[0] as Request
    expect(health.headers.get(ID_TOKEN_HEADER)).toBeNull()
    expect(vote.url).toBe('http://api.test/api/v1/workflow/tasks/task-1/votes')
    expect(vote.headers.get('Authorization')).toBe('Bearer token-123')
    expect(vote.headers.get(ID_TOKEN_HEADER)).toBe('id-token-1')
    expect(getIdToken).toHaveBeenCalledTimes(1)
  })
})

describe('castVote', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reports reauthentication_required with the backend message', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          Response.json(
            { code: 'reauthentication_required', message: 'Sign in again.' },
            { status: 401 },
          ),
        ),
    )
    const client = createApiClient('http://api.test', () => Promise.resolve('token-123'))

    await expect(castVote(client, 'task-1', { decision: 'Approve' })).rejects.toMatchObject({
      status: 401,
      code: 'reauthentication_required',
      message: 'Sign in again.',
    })
  })
})
