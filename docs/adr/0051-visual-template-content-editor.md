# 0051: Visual editor for structured template content: a block editor over the content model, ProseMirror only for inline text, forms for expressions

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

Template designers edited the content of structured templates (ADR 0007) as JSON in a text area (DMS-98). The product owner's request (DMS-128): "I do not want to edit JSON; everything should be possible in an editor." The editor must cover the whole content model of `modules/templates/api` (`Content.scala`): sections with title, id and lock; headings (levels 1–6) and paragraphs with inline text (bold, italic, underline), placeholders with an optional format and line breaks; bulleted and numbered lists whose items are block lists; tables whose cells are block lists, with header rows; clause references (key and version); conditionals (CEL condition, body, otherwise); repeats (CEL collection, item name, body); page breaks; computed fields (name, CEL expression, ordered); reference fields (source, attribute → field mappings, ADR 0023).

Constraints:

- The stored format stays the content model, unchanged, and the API stays compatible. Editor state and model must map losslessly in both directions, with round-trip tests.
- Server validation stays the truth: the check endpoint compiles the content against the document class and reports issues with section id, JSON-pointer-like block path, part and line/column (`Location`). Messages must be shown at the affected element.
- CEL (ADR 0020) is part of the model. Forms where a form fits, a guided input with validation where not.
- Keyboard reordering, labels and focus management (accessibility); German and English (ADR 0038); no lost changes.
- A rich-text library is welcome if needed, with exact versions and a reasonable bundle size.

## Decision

The editor is a **React block editor over the content model**: every section, block, list item, table cell, computed field and reference field is a React component with its own controls, and the editor state is the content model with a browser-only `uid` per node (`editor/content-model.ts`: `toEditor`/`fromEditor`). Only the **inline content of headings and paragraphs** is edited with **ProseMirror** (`prosemirror-model`, `-state`, `-view`, `-history`, `-keymap`, `-commands`, `-transform`, pinned to exact versions), with a schema that allows exactly the model's inlines: text with the marks bold/italic/underline, a placeholder atom (path, format) and a hard break. Conditions, computed fields and repeat collections are edited with **forms** (rules, arithmetic steps, a list-field picker) that write CEL and read back the CEL they write; any other expression stays a **guided expression input** (field insertion at the caret, bracket and string check while typing, server check with line and column). The JSON is kept as an optional **"Advanced" view**, hidden by default, that can apply edited JSON after a structural check.

## Alternatives considered

| Criterion | Chosen: block editor + ProseMirror for inline text | One ProseMirror/TipTap document for the whole content | Lexical for the whole content | Own `contenteditable` without a library |
|---|---|---|---|---|
| Mapping to the content model | Trivial for blocks (same tree plus `uid`); inline mapping is ~60 lines with a 1:1 schema | Needs node types for conditionals, repeats, tables with block cells, list items with block lists, clause refs, sections with attributes; every model change needs schema, parser and serializer changes | Same as left, with Lexical's node classes | Trivial mapping, but text editing (selection, IME, undo, paste) is all ours |
| Forms inside the content (conditions, repeat, clause, table header rows) | Plain React forms next to the text | Node views with embedded React forms; focus and selection fights between the editor and inputs | Decorator nodes with the same problems | Plain React forms |
| Lossless round trip | Untouched blocks keep their stored form exactly (also non-canonical inline runs); an edited text block is normalized only in ways that do not change the rendering | Whole document re-serialized on every edit; normalization everywhere | Same | Exact |
| Server issues at the element | Paths map to `uid`s by walking the tree like the compiler (`placeIssues`) | Paths must be mapped to document positions | Same | Like chosen |
| Keyboard reordering and labels | Native buttons and a drag handle per element, standard ARIA | Custom commands and announcements inside the editor | Same | Like chosen |
| Bundle size | ProseMirror core packages about 210 kB minified (64 kB gzip), only in the template page chunk | The same ProseMirror packages plus TipTap core and extensions | Lexical core plus React, list and table packages, of a similar order | None |
| Maintenance | Mature, small, stable APIs (ProseMirror 1.x for years); our schema is ~80 lines | TipTap releases often (3.x minors monthly); extension API on top of ProseMirror | Active, API still evolving (0.x) | High: browser differences in `contenteditable` |

TipTap was the obvious candidate, but its value is the extension layer for a document editor with many node types; for an inline-only editor that layer adds size and release churn without saving code. Lexical would be chosen for a full document editor built from scratch, not for a single text block.

## Consequences

### Positive

- Designers edit everything the model supports without JSON: sections (add, rename, id, lock, collapse, reorder with keyboard, buttons and drag and drop, remove with confirmation), text with formatting and placeholders picked from the class fields (with amount parts and formats by field type), lists, tables (rows, columns, header rows), clauses, conditions, repeats with typed item variables, page breaks, computed fields in order, reference fields.
- The stored format and the API are unchanged; no backend change and no migration. `fromEditor(toEditor(x))` equals `x` for content as the server writes it, and the canonical form (defaults filled) for older or hand-written content; tests cover every block and inline type.
- Server issues are shown at the element (invalid inputs marked with `aria-invalid`, the message as accessible description) and in a summary with "Show" links that move the focus there; issues stay attached to their element when blocks are moved after a check.
- Leaving the page with unsaved changes asks first (router blocker); closing or reloading the tab triggers the browser warning. "Discard changes" restores the last saved state.
- Released and other non-draft versions are shown in the same editor, read-only, instead of raw JSON.
- ProseMirror is loaded only with the template page (route code splitting); the main bundle is unchanged.

### Negative / Cost

- An edited text block is normalized by ProseMirror: adjacent text runs with the same marks are merged, empty runs dropped, marks ordered Bold, Italic, Underline. The rendering is the same, but the version comparison (ADR 0033) of such a block can show a formatting-only change. Untouched blocks are not touched.
- The condition and arithmetic forms cover common shapes only (comparisons, presence, yes/no, list contains, joined by one kind of `&&`/`||`; `a op b op c` over numeric paths). Anything else is edited as an expression; switching back to the form is offered only when the expression has a form shape.
- There is no clause library API yet; a clause is referenced by key and version typed in.
- The field picker uses the designer fields endpoint of the saved version, so a computed field or repeat item added since the last save is offered from the editor state, but fields of a changed document class appear only after saving.
- Moving blocks works within their list (buttons, drag handle with arrow keys, drag and drop), not across lists; moving a block into another section means removing and re-adding it.
- The template page chunk grows from 23 kB to 298 kB minified (6 kB to 86 kB gzip): about 210 kB ProseMirror, the rest the editor. Only template designers load it.
- New npm dependencies (seven ProseMirror packages, MIT, by the same maintainer) that need updates.

## Follow-ups

- Must be done: a clause picker once the clause library has an API (key, versions, preview).
- Must be done: check in the browser with real templates (long templates, performance with many text blocks, screen reader pass with NVDA/VoiceOver).
- Should be done: moving blocks across lists (drag and drop between sections, "move to section" menu).
- Should be done: edit the format of an inserted placeholder without removing it.
- Could be done: a live check (debounced check endpoint) instead of the "Check" button; cel-js for client-side type checks (ADR 0020 follow-up).
- Ruled out: storing editor state (ProseMirror JSON, `uid`s) instead of the content model.

## Reversibility

**high**: The decision is confined to the frontend (`frontend/src/components/templates/content-editor.tsx` and `editor/`). The stored format and the API are unchanged, so the editor can be replaced (e.g. by a full-document TipTap editor) without data migration; ProseMirror sits behind the small `InlineEditor` component and the `inline-schema.ts` mapping.
