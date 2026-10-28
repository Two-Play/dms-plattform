import { describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '@/api/errors'
import { isReauthenticationRequired, reauthenticate, stepUpSigninArgs } from './step-up'

describe('step-up', () => {
  it('recognizes reauthentication_required only on 401', () => {
    expect(
      isReauthenticationRequired(
        new ApiRequestError(401, 'Sign in again.', 'reauthentication_required'),
      ),
    ).toBe(true)
    expect(isReauthenticationRequired(new ApiRequestError(401, 'x', 'invalid_token'))).toBe(false)
    expect(isReauthenticationRequired(new ApiRequestError(403, 'x', 'forbidden'))).toBe(false)
    expect(isReauthenticationRequired(new Error('x'))).toBe(false)
  })

  it('signs in again with prompt=login and max_age=0, returning to the given page', async () => {
    const signinRedirect = vi.fn(() => Promise.resolve())

    await reauthenticate(signinRedirect, '/documents/42?tab=workflow')

    expect(signinRedirect).toHaveBeenCalledWith({
      prompt: 'login',
      max_age: 0,
      state: { returnTo: '/documents/42?tab=workflow' },
    })
  })

  it('returns to the current page by default', async () => {
    window.history.replaceState({}, '', '/tasks?open=1')
    const signinRedirect = vi.fn(() => Promise.resolve())

    await reauthenticate(signinRedirect)

    expect(signinRedirect).toHaveBeenCalledWith(stepUpSigninArgs('/tasks?open=1'))
  })
})
