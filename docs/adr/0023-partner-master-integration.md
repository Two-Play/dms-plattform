# 0023: Partner master integration: live lookup through a port, snapshot into field values

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-03.1 (DMS-43): when filling in a contract, the author picks the contract partner from the partner master of a connected business system (CRM/ERP), so that name and address are taken over correctly. Search must find partners by name and identifier. Taken-over values are marked "taken over from system" and cannot be edited freely. If the partner master is unreachable, the author gets a clear message and can save the draft and continue later.

Constraints:

- Business concept 4.2 already names the field kind "reference field" (value from master data, taken over after selection) next to computed fields, which live in the template (ADR 0020). Business concept 4.3 requires the origin of every value (manual / system X / computed); `ValueOrigin.System(source)` exists since DMS-37.
- A released document must keep the values it was approved with (ADR 0021); a later change in the partner master must not change existing versions.
- No real partner master is connected yet; development and tests need a stand-in. The HTTP contract of the eventual system is not known.
- Calls to external systems must not hold a database connection (no forked fibers or long waits inside `Db.transact`, CLAUDE.md) and must fail fast when the system is down.
- Module rule (ADR 0001): modules depend on other modules' `*-api` only.

## Decision

A port `PartnerDirectory` (`search`, `get`) in the new module `integration-api` with an HTTP adapter (`HttpPartnerDirectory`: configurable base URL, timeout per request, bearer token, small circuit breaker, typed errors `Unavailable` / `InvalidResponse`) and an in-memory demo directory in `integration`, selected by `dms.partners.directory` (`demo` | `http`). Lookups are **live and not cached**; what a document keeps is a **snapshot**: picking a partner takes the mapped attributes over into the draft's field values with origin `System("partner-master")`, including the identifier as source reference. Which fields a partner fills is declared in the **template** as a reference field (`TemplateContent.references`), checked by the template compiler. The documents module rejects manual values for these fields and for every other value from a business system. No new table, no migration.

## Alternatives considered

Where the lookup result lives:

| Criterion | Chosen: snapshot into field values, no cache | Alternative A: local partner table synced from the partner master (outbox/job) | Alternative B: store only the partner id, resolve on read |
|---|---|---|---|
| Document keeps the values it was created/approved with | yes, values are part of the version package and the metadata snapshot | yes, if copied into the document as well | no, a renamed partner changes old documents; breaks ADR 0021 |
| Works while partner master is down | saving yes; picking no (clear message) | picking also works (stale data possible) | reading old documents breaks too |
| Effort now | low: port, adapter, no migration | high: table with RLS, sync job, deletion/merge handling, initial load | low, but wrong |
| Data freshness when picking | always current | as fresh as the last sync | current |
| Personal/business data held by the DMS | only what documents use | the whole partner master per tenant | only ids |

Where the binding "field ← partner attribute" is declared:

| Criterion | Chosen: template (`references`, like computed fields) | Alternative C: attribute of the field definition (metadata model, ADR 0019) | Alternative D: fixed field-key convention (`partnerName`, ...) |
|---|---|---|---|
| Matches business concept | yes: "reference field" is a field kind of templates (4.2) | partly: the field would always be a reference, in every class | no |
| Several partners per document (e.g. client and subcontractor) | yes, one reference field per role | needs one field definition per role and source | no |
| Change needed | `TemplateContent.references`, compiler check, JSON default `[]` (stored format 1 stays readable) | new column in `dms.field_definition` (migration), admin API and UI | none, but implicit and fragile |
| Validation at design time | yes, against the class profile (text fields, known attributes, identifier mapped) | yes | no |

## Consequences

### Positive

- The document is self-contained: taken-over values are in the version package, in the audit trail (`document.draft-changed` with before/after) and, on approval, in the metadata snapshot; the identifier in a field is the source reference.
- An unreachable partner master never blocks work on a draft: saving does not call it; picking fails within the timeout (default 3 s) with `503 partner_directory_unavailable` and a message that says the draft can be saved; after repeated failures the circuit breaker answers immediately.
- The HTTP adapter is a thin, replaceable layer: a different partner master needs only a new adapter behind the same port.
- "Not editable" is enforced in the documents module for every business-system value (`not_editable`), not only in the UI; an unchanged echo of a system value keeps its origin.

### Negative / Cost

- Picking a partner needs the partner master online; there is no offline search.
- A changed partner (new address) is not propagated to drafts; the author has to pick the partner again. Approved versions are intentionally never updated.
- The HTTP contract (`GET <base>/partners?q=&limit=`, `GET <base>/partners/{id}`, JSON fields `id`, `name`, `street`, `postalCode`, `city`, `country`, `vatId`; tenant in header `X-Dms-Tenant`) is our assumption; the real system will likely need an adapter mapping.
- One configuration for all tenants; per-tenant endpoints and credentials are not supported yet.
- The partner attribute names exist twice (`ReferenceSource.PartnerMaster.attributes` in templates-api, `Partner.Attributes` in integration-api); a test in `app` keeps them equal.

## Follow-ups

- Must be done: connect the real partner master (adapter mapping, credentials from the secret store, per-tenant configuration).
- Must be done: open a saved draft again in the UI ("continue later" currently ends at saving from `/documents/new`).
- Must be done: template editor UI for reference fields (today only through the template API).
- Must be done: health/readiness information for the partner master (circuit breaker state).
- Ruled out for now: a local partner cache or sync (Alternative A); revisit if offline picking or search across many systems becomes a requirement.

## Reversibility

**high**: The port hides the adapter; switching to a synchronized local table (Alternative A) only changes the `PartnerDirectory` implementation. Reference fields are optional content (`[]` by default); stored field values keep working without them because they are ordinary values with a `System` origin.
