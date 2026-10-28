import type { SigninRedirectArgs } from 'oidc-client-ts'
import { ApiRequestError } from '@/api/errors'
import type { SignInState } from './oidc'

/** Error code of the API when an action needs a more recent sign-in (step-up, ADR 0024). */
export const REAUTHENTICATION_REQUIRED = 'reauthentication_required'

/** Whether a failed API call asks the user to sign in again before repeating the action. */
export function isReauthenticationRequired(error: unknown): boolean {
  return (
    error instanceof ApiRequestError &&
    error.status === 401 &&
    error.code === REAUTHENTICATION_REQUIRED
  )
}

/**
 * Arguments for a fresh interactive sign-in: `prompt=login` and `max_age=0` make ZITADEL ask for the
 * credentials again, so the new ID token carries a current `auth_time`. `returnTo` is restored by
 * the /callback route.
 */
export function stepUpSigninArgs(returnTo: string): SigninRedirectArgs {
  const state: SignInState = { returnTo }
  return { prompt: 'login', max_age: 0, state }
}

/** The current page (path, query and hash) as return target after the step-up sign-in. */
export function currentLocation(): string {
  const { pathname, search, hash } = window.location
  return `${pathname}${search}${hash}`
}

/**
 * Signs the user in again and returns to `returnTo` (default: the current page). The action that
 * failed with `reauthentication_required` is not repeated automatically: after the redirect the
 * user casts the vote again, which then carries the new ID token.
 */
export function reauthenticate(
  signinRedirect: (args?: SigninRedirectArgs) => Promise<void>,
  returnTo: string = currentLocation(),
): Promise<void> {
  return signinRedirect(stepUpSigninArgs(returnTo))
}
