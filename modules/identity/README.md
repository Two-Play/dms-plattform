# Module `identity`

Sign-in via ZITADEL (ADR 0005): `TokenVerifier` validates JWT access tokens against the issuer's JWKS (signature, issuer, audience = project id, expiry), `TenantDirectory` maps the user's ZITADEL organization to a tenant (`dms.tenant_by_zitadel_org`), `Authenticator` combines both into a `Principal`. `Authenticator.requireRecentSignIn` is the step-up check for sensitive actions.

Step-up (DMS-86, ADR 0024 addendum): ZITADEL access tokens carry no `auth_time`. For step-up-protected requests the client sends the OIDC ID token of its session in header `X-DMS-Id-Token`; `TokenVerifier.verifyIdToken` checks it (JWKS, issuer, audience = web client id `dms.auth.clientId`, `azp`, expiry, `sub` and `at_hash` matching the access token) and `Authenticator.authenticate(token, idToken)` sets `Principal.authenticatedAt` to the later `auth_time` of both tokens. `Principal.idToken` records whether the ID token was missing, accepted or rejected, to explain a failed step-up.

Tokens must be requested with the scopes `urn:zitadel:iam:org:project:id:<project id>:aud`, `urn:zitadel:iam:user:resourceowner` and `urn:zitadel:iam:org:project:roles`.

User directory (DMS-110, ADR 0037): `UserDirectory` resolves user ids to display names and emails. `ZitadelUserDirectory` asks ZITADEL's user service v2 (`POST /v2/users`, filtered by ids and organization, at most 100 ids per request) with the personal access token of a read-only service user; `CachedUserDirectory` keeps answers per organization (`dms.users.cacheSeconds`, `dms.users.cacheSize`); `UserDirectories.layer` picks ZITADEL when `dms.users.tokenFile` or `dms.users.token` is set, otherwise `UserDirectory.none` (ids stay unresolved). `TenantOrganizations` reads the tenant's ZITADEL organization inside the tenant's transaction. The app exposes it as `GET /api/v1/users?ids=…` (`dms.app.UserApi`). For development, `scripts/zitadel-setup.sh` creates the machine user `dms-directory` (`ORG_OWNER_VIEWER`) and writes its token to `.local/zitadel/directory.pat`.

Single module without an `api`/`core` split.
