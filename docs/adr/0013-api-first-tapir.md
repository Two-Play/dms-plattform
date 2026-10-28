# 0013: API-first with Tapir on ZIO HTTP, OpenAPI is generated

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The platform is used by its own web frontend and by integrations (ERP, HR, e-signature, customer systems). An up-to-date, machine-readable API description is a prerequisite for client generation and for integration partners. Hand-maintained OpenAPI files tend to drift apart from the code.

## Decision

Endpoints are defined in Scala as Tapir endpoint descriptions (`1.14.1`), run on ZIO HTTP (`tapir-zio-http-server`) and serialized with zio-json. The OpenAPI specification is generated from these descriptions and served with Swagger UI.

## Alternatives considered

| Criterion | Chosen: Tapir on ZIO HTTP | ZIO HTTP Endpoint API directly | http4s | GraphQL (Caliban) |
|---|---|---|---|---|
| Ecosystem fit (0003) | ZIO | ZIO | Typelevel, wrong ecosystem | ZIO |
| OpenAPI generation | Mature | Available, less mature | Via Tapir or add-on | No OpenAPI, GraphQL schema |
| Server replaceable | Yes (Netty, Pekko, http4s) | No | n/a | n/a |
| Security definitions, error variants | Typed in the endpoint | Available | Manual | Own model |
| Suitability for integration partners | High (REST, OpenAPI) | High | High | Medium, GraphQL knowledge required |
| File upload/download, streaming | Supported | Supported | Supported | Cumbersome |
| Documentation, adoption | High | Medium | High | Medium |

## Consequences

### Positive

- OpenAPI is always in sync with the code; the frontend client can be generated.
- The endpoint description is separate from the server implementation; replacing ZIO HTTP would be possible.
- Typed inputs, outputs and errors per endpoint.

### Negative / Cost

- An additional abstraction layer on top of ZIO HTTP; compiler error messages for complex endpoints are hard to read.
- Tapir and ZIO HTTP have separate release cycles; version compatibility must be watched.
- API-first in the strict sense (specification first) does not apply: the source is Scala code, not YAML.

## Follow-ups

- Endpoint descriptions in `*-api` modules, server logic in `*-core`, assembly in `app`.
- Generate the OpenAPI file in the build and version it; breaking-change check in CI.
- Define API versioning (path `/api/v1`) and a uniform error format (RFC 9457 Problem Details).
- Authentication as a Tapir security input (JWT from 0005).
- Ruled out: GraphQL as the primary API; hand-written OpenAPI files.

## Reversibility

**medium**: Switching the server underneath Tapir is low effort. Moving away from Tapir affects all endpoint definitions; the externally visible API and its OpenAPI description can remain unchanged in the process.
