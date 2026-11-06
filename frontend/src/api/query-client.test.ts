import { describe, expect, it } from 'vitest'
import { ApiRequestError } from './errors'
import { retryQuery } from './query-client'

describe('retryQuery', () => {
  it.each([403, 404])('does not repeat a %i', (status) => {
    expect(retryQuery(0, new ApiRequestError(status, 'x'))).toBe(false)
  })

  it.each([500, 503])('tries a %i once more', (status) => {
    expect(retryQuery(0, new ApiRequestError(status, 'x'))).toBe(true)
    expect(retryQuery(1, new ApiRequestError(status, 'x'))).toBe(false)
  })

  it('tries a failed fetch once more', () => {
    expect(retryQuery(0, new TypeError('Failed to fetch'))).toBe(true)
  })
})
