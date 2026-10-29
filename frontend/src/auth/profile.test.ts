import { describe, expect, it, vi } from 'vitest'
import { oidcScopes } from './oidc'
import { displayName, greetingName } from './profile'

vi.mock('@/config', () => ({
  config: { oidcIssuer: 'http://localhost:8085', oidcClientId: 'client', apiBaseUrl: '' },
}))

describe('displayName', () => {
  it('prefers the full name', () => {
    expect(displayName({ name: 'Ada Author', preferred_username: 'author' })).toBe('Ada Author')
  })

  it('builds the name from given and family name', () => {
    expect(displayName({ given_name: 'Ada', family_name: 'Author' })).toBe('Ada Author')
  })

  it('falls back to the user name, then the email', () => {
    expect(displayName({ preferred_username: 'author', email: 'a@example.com' })).toBe('author')
    expect(displayName({ email: 'a@example.com', name: '  ' })).toBe('a@example.com')
  })

  it('is undefined without any name claim', () => {
    expect(displayName({})).toBeUndefined()
    expect(displayName(undefined)).toBeUndefined()
  })
})

describe('greetingName', () => {
  it('uses the given name, else the display name', () => {
    expect(greetingName({ given_name: 'Ada', name: 'Ada Author' })).toBe('Ada')
    expect(greetingName({ preferred_username: 'author' })).toBe('author')
  })
})

describe('oidcScopes', () => {
  it('requests profile and email so that name claims are available', () => {
    const scopes = oidcScopes('p1').split(' ')
    expect(scopes).toEqual(expect.arrayContaining(['openid', 'profile', 'email']))
    expect(scopes).toContain('urn:zitadel:iam:org:project:id:p1:aud')
  })
})
