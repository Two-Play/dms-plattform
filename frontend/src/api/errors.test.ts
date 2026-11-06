import { describe, expect, it } from 'vitest'
import { partnerRequestError, requestError } from './errors'

const failed = new Response(null, { status: 422 })

describe('requestError', () => {
  it('keeps the message of the backend', () => {
    const error = requestError(failed, { code: 'invalid_docx', message: 'broken' }, 'fallback')
    expect(error.message).toBe('broken')
    expect(error.code).toBe('invalid_docx')
  })

  it('does not blame the partner master for a 502 or 503 of any other endpoint', () => {
    for (const status of [502, 503]) {
      const error = requestError(new Response(null, { status }), undefined, 'failed with HTTP 503')
      expect(error.code).toBeUndefined()
      expect(error.status).toBe(status)
    }
  })

  it('keeps the wait of a busy service', () => {
    const busy = new Response(null, { status: 503, headers: { 'Retry-After': '7' } })
    expect(
      requestError(busy, { code: 'unavailable', message: 'busy' }, 'x').retryAfterSeconds,
    ).toBe(7)
  })
})

describe('partnerRequestError', () => {
  it('reads a 502 or 503 without a body as an unreachable partner master', () => {
    for (const status of [502, 503]) {
      const error = partnerRequestError(new Response(null, { status }), undefined, 'x')
      expect(error.code).toBe('partner_directory_unavailable')
    }
  })

  it('keeps what the backend said', () => {
    const body = { code: 'partner_directory_invalid_response', message: 'broken answer' }
    const error = partnerRequestError(new Response(null, { status: 502 }), body, 'x')
    expect(error.code).toBe('partner_directory_invalid_response')
    expect(error.message).toBe('broken answer')
  })

  it('leaves other failures alone', () => {
    const error = partnerRequestError(failed, undefined, 'x')
    expect(error.code).toBeUndefined()
  })
})
