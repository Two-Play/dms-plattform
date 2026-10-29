import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import { config } from '@/config'

/**
 * Scopes requested from ZITADEL:
 * - `offline_access` yields a refresh token for silent renewal.
 * - `urn:zitadel:iam:user:resourceowner` adds the user's organization
 *   (`urn:zitadel:iam:user:resourceowner:id`), which the backend maps to the tenant.
 * - `urn:zitadel:iam:org:project:roles` adds the project roles claim for authorization.
 * - the project audience scope puts the DMS project into the access token's `aud` claim,
 *   which the backend validates.
 */
export function oidcScopes(projectId: string | undefined): string {
  const scopes = [
    'openid',
    'profile',
    'email',
    'offline_access',
    'urn:zitadel:iam:user:resourceowner',
    'urn:zitadel:iam:org:project:roles',
  ]
  if (projectId) scopes.push(`urn:zitadel:iam:org:project:id:${projectId}:aud`)
  return scopes.join(' ')
}

/** State carried through the authorization redirect. */
export interface SignInState {
  returnTo?: string
}

/** Single UserManager shared by the React auth context and the API client. */
export const userManager = new UserManager({
  authority: config.oidcIssuer,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code', // authorization code flow; oidc-client-ts always uses PKCE
  scope: oidcScopes(config.oidcProjectId),
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // ZITADEL puts name and email into the ID token only with "user info inside ID token"; the
  // userinfo endpoint always has them for the `profile` and `email` scopes (DMS-109).
  loadUserInfo: true,
  automaticSilentRenew: true,
})

/** Current access token, or undefined when signed out or expired. */
export async function getAccessToken(): Promise<string | undefined> {
  const user = await userManager.getUser()
  return user && !user.expired ? user.access_token : undefined
}

/** ID token of the current session, sent for step-up-protected requests (DMS-86). */
export async function getIdToken(): Promise<string | undefined> {
  const user = await userManager.getUser()
  return user && !user.expired ? user.id_token : undefined
}
