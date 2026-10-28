# Module `identity`

Sign-in via ZITADEL (ADR 0005): `TokenVerifier` validates JWT access tokens against the issuer's JWKS (signature, issuer, audience = project id, expiry), `TenantDirectory` maps the user's ZITADEL organization to a tenant (`dms.tenant_by_zitadel_org`), `Authenticator` combines both into a `Principal`. `Authenticator.requireRecentSignIn` is the step-up check for sensitive actions.

Step-up (DMS-86, ADR 0024 addendum): ZITADEL access tokens carry no `auth_time`. For step-up-protected requests the client sends the OIDC ID token of its session in header `X-DMS-Id-Token`; `TokenVerifier.verifyIdToken` checks it (JWKS, issuer, audience = web client id `dms.auth.clientId`, `azp`, expiry, `sub` and `at_hash` matching the access token) and `Authenticator.authenticate(token, idToken)` sets `Principal.authenticatedAt` to the later `auth_time` of both tokens. `Principal.idToken` records whether the ID token was missing, accepted or rejected, to explain a failed step-up.

Tokens must be requested with the scopes `urn:zitadel:iam:org:project:id:<project id>:aud`, `urn:zitadel:iam:user:resourceowner` and `urn:zitadel:iam:org:project:roles`.

Single module without an `api`/`core` split.
