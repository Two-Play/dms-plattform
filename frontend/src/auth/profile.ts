/** The name claims of an OIDC profile the UI uses (ID token and userinfo, merged by oidc-client-ts). */
export interface ProfileClaims {
  name?: string
  given_name?: string
  family_name?: string
  preferred_username?: string
  email?: string
}

function text(value: string | undefined): string | undefined {
  const trimmed = value?.trim()
  return trimmed ? trimmed : undefined
}

/**
 * Display name of the signed-in user: full name, given and family name, user name or email,
 * in this order; undefined when the profile has none of them.
 */
export function displayName(profile: ProfileClaims | undefined): string | undefined {
  if (!profile) return undefined
  const given = text(profile.given_name)
  const family = text(profile.family_name)
  return (
    text(profile.name) ??
    (given && family ? `${given} ${family}` : (given ?? family)) ??
    text(profile.preferred_username) ??
    text(profile.email)
  )
}

/** Name for a greeting: the given name if known, else the display name. */
export function greetingName(profile: ProfileClaims | undefined): string | undefined {
  return text(profile?.given_name) ?? displayName(profile)
}
