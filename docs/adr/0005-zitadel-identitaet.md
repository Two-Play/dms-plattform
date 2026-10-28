# 0005: ZITADEL as identity provider

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The platform needs OIDC login, multi-tenancy, federation with customer IdPs (e.g. Entra ID), technical users for integrations and re-authentication (step-up) before approvals. User management and password storage should not be built in-house.

## Decision

ZITADEL is the identity provider, pinned locally to `ghcr.io/zitadel/zitadel:v4.19.5` with its own PostgreSQL database. ZITADEL organizations represent tenants; ZITADEL manages only coarse-grained roles, document-level permissions (ABAC) are decided by the platform.

## Alternatives considered

| Criterion | Chosen: ZITADEL | Keycloak | Microsoft Entra ID (federation only) | Build in-house |
|---|---|---|---|---|
| Tenant model | Organizations natively, flat | Realms (heavyweight) or Organizations | One tenant per customer, external | Free, but must be built |
| License | AGPL-3.0 since v3 | Apache-2.0 | Proprietary, SaaS | Own |
| Operations | One Go binary, Postgres | JVM, Postgres, higher resource needs | No own operations | Entirely own |
| Federation with customer IdPs | Yes, per organization | Yes, per realm | Natively only in the Microsoft world | Must be built |
| Service users / client credentials | Yes | Yes | Yes | Must be built |
| Step-up (`prompt=login`, `max_age`) | Supported | Supported | Supported | Must be built |
| Security risk | Low | Low | Low | High |

## Consequences

### Positive

- Tenant self-administration (own admins, own IdP federation) without in-house development.
- Standard OIDC: the platform only validates JWTs (nimbus-jose-jwt `10.10`, module `identity`).
- Low resource needs compared to Keycloak.

### Negative / Cost

- AGPL-3.0: the implications for distribution and for operation as SaaS are legally unresolved.
- Organizations are flat; group structures (holding, subsidiary) must be modelled in the platform.
- An additional database and an additional service to operate, back up and monitor.
- Permissions are split across two systems (roles in ZITADEL, ABAC in the platform).

## Follow-ups

- Legal review of AGPL-3.0 for the operating model (open, before the first customer goes live).
- Tenant hierarchy as a separate table in the platform; mapping organization ID → tenant.
- Keep the role catalogue in ZITADEL small; ABAC rules in the `authz` module.
- Step-up: the client requests `prompt=login` or `max_age`; the backend checks `auth_time` in the token before approval actions.
- Create and document service users for integrations (module `integration`).
- A separate Postgres instance or database for ZITADEL in Docker Compose and in the backup concept.
- Ruled out: own password storage, own login screens.

## Reversibility

**medium**: The platform uses only standard OIDC and its own tenant IDs; a switch to Keycloak affects configuration, claim mapping and user migration. Transferring password hashes and the tenants' federation configurations is the most costly part.
