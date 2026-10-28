# 0007: Structured content format as the primary format, DOCX for import/export only

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Contracts, policies and manuals are generated from templates and reusable clauses. Required: a clause library, a deviation report (which standard clause was changed), version comparison at section level, placeholders for metadata and deterministic PDF/A rendering (0008). Legal departments are used to working in Word.

## Decision

The primary format is a custom structured content model (a JSON tree of sections, clauses, paragraphs, tables and placeholders with stable IDs). DOCX is only imported (with structure recognition) and exported, and is never stored as the source of truth.

## Alternatives considered

| Criterion | Chosen: Structured model | DOCX as source (Word templates, rendering via LibreOffice) | Markdown |
|---|---|---|---|
| Clause library with reuse | Clause as a node with ID and version | Only via text snippets/content controls, fragile | Only via text replacement |
| Deviation report | Node comparison against library version | Text comparison in OOXML, unreliable | Text comparison |
| Version comparison | Structural, per section/clause | Word compare, cannot be evaluated server-side | Line-based |
| Rendering determinism | High (HTML from the model) | Low (LibreOffice layout varies with version and fonts) | High |
| Expressiveness (tables, numbering, footnotes) | Extensible as needed | Complete | Low |
| Acceptance by legal departments | Medium, web editor is unfamiliar | High | Low |
| Development effort | High (editor, import, export) | Medium | Low |

## Consequences

### Positive

- Clauses, deviations and version differences can be evaluated in business terms and proven in an audit-proof way.
- Rendering is reproducible, a prerequisite for PDF/A archiving.
- Placeholders and metadata are typed instead of hidden in running text.

### Negative / Cost

- A custom web editor is required; this is the largest single effort in the frontend.
- DOCX import is lossy; complex Word formatting is lost or simplified.
- Users who want to work in Word must export, edit and re-import; the re-import creates a new version with structure matching.
- Acceptance risk in legal departments.

## Follow-ups

- Define a versioned schema of the content model (module `templates-api` / `documents-api`), including a migration path for schema changes.
- Choice of editor in the frontend (e.g. ProseMirror-based) as a separate ADR.
- DOCX import: define the supported formatting scope and show losses to the user.
- DOCX export for negotiation rounds with external parties.
- Ruled out: Word files as the stored working version; LibreOffice in the rendering path.

## Reversibility

**high**: Clause library, deviation report, version comparison and rendering are built on the model. Switching to DOCX as the source would devalue these features. Exporting all content to DOCX, on the other hand, is possible at any time.
