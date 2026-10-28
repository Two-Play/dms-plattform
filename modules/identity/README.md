# Module `identity`

Sign-in via ZITADEL (ADR 0005): `TokenVerifier` validates JWT access tokens against the issuer's JWKS (signature, issuer, audience = project id, expiry), `TenantDirectory` maps the user's ZITADEL organization to a tenant (`dms.tenant_by_zitadel_org`), `Authenticator` combines both into a `Principal`. `Authenticator.requireRecentSignIn` is the step-up check for sensitive actions.

Tokens must be requested with the scopes `urn:zitadel:iam:org:project:id:<project id>:aud`, `urn:zitadel:iam:user:resourceowner` and `urn:zitadel:iam:org:project:roles`.

Single module without an `api`/`core` split.
