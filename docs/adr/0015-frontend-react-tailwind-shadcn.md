# 0015: Frontend with React, Tailwind CSS and shadcn/ui

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The web UI is data-heavy (document lists with facets, task inbox, version comparison, compliance dashboard) and contains one demanding component: a structured editor with locked sections, typed placeholders, conditional blocks and comments anchored to text (business concept 4.1–4.2, ADR 0007). The editor is built on ProseMirror via TipTap in every option. One developer builds and maintains the UI; the repository is public. Sign-in uses OIDC against ZITADEL (ADR 0005); the API is described by OpenAPI from Tapir (ADR 0013).

Versions checked on 2026-10-28: React 19.3.1, Vite 8.3.5, Tailwind CSS 4.3.3, shadcn 4.23.0, TanStack Table 9.3.1, TanStack Router 1.170.46, TanStack Query 5.105.0, react-hook-form 7.91.0, TipTap 3.32.0, oidc-client-ts 3.5.0, openapi-typescript 7.13.0, openapi-fetch 0.17.0, flowbite-react 0.12.17, PrimeReact 10.9.9 (MIT) and 11.2.2 (PrimeUI license).

## Decision

The frontend uses React with TypeScript and Vite, styled with Tailwind CSS. UI components come from shadcn/ui (Radix primitives, component source copied into the repository). Data views use TanStack Table, routing TanStack Router, server state TanStack Query, forms react-hook-form. The API client is generated from the OpenAPI description.

## Alternatives considered

| Criterion | Chosen: Tailwind + shadcn/ui | Tailwind + Flowbite React | PrimeReact 10 | PrimeReact 11 | Mantine | Vue 3 + PrimeVue |
|---|---|---|---|---|---|---|
| License | MIT | MIT | MIT | PrimeUI: free Community License only below size limits (< 5 devs, < 10 employees, < 1 M USD revenue), license key, yearly renewal; otherwise paid per developer | MIT | PrimeUI terms apply to current versions |
| Maturity | High, widely used | 0.x, API changes likely | High, maintenance line only | Current line | High | High |
| Data table (sort, filter, paging, virtualization) | TanStack Table, very capable, UI built ourselves | Basic table only | Ready-made DataTable/TreeTable | Ready-made | Basic table plus extensions | Ready-made DataTable |
| Control over look and markup | Full: component code lives in the repo | High | Medium (themes or unstyled mode) | Medium | Medium | Medium |
| Fit with TipTap editor styling | Seamless (Tailwind on both) | Seamless | Separate styling systems | Separate | Good | Good (TipTap has Vue bindings) |
| Effort for one developer | Medium | Medium to high for data views | Low | Low | Low to medium | Low |
| Future risk | Low | Medium | Dead end: new features only in v11 | License terms can change, key handling in a public repo | Low | Same license question as PrimeReact |

## Consequences

### Positive

- No license risk or license keys; everything is MIT.
- Components are our own code: accessibility fixes, design changes and editor-specific widgets (placeholder chips, locked-section markers, comment margins) need no workarounds.
- One styling system for application UI and editor content.
- Large ecosystem and many examples, which also helps AI-assisted development.

### Negative / Cost

- More assembly work: tables, filter bars, date ranges and tree views are built from TanStack and Radix building blocks instead of being ready-made.
- Copied shadcn/ui components do not update automatically; updates are deliberate merges.
- Consistency depends on our own discipline (design tokens, a component catalogue).

## Follow-ups

- T-00.10 (DMS-27): set up `frontend/` with Vite, React, TypeScript, Tailwind, shadcn/ui, TanStack Router/Query, oidc-client-ts and an OpenAPI-generated client.
- Define design tokens (colors, spacing, typography) in the Tailwind theme before building screens.
- Add a reusable data table component on TanStack Table (sorting, filtering, paging, column visibility, server-side mode) early, since most screens depend on it.
- Excluded: mixing component libraries; PrimeReact 11 unless the license question is decided separately.

## Reversibility

**medium**: Screens depend on component APIs, but business logic sits in hooks and the generated API client. Switching the component library means rewriting the presentation layer, not data access or the editor core.
