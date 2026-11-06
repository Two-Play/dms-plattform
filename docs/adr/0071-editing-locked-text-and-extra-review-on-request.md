# 0071: Authors edit locked text with an active request in the block editor of templates; the request alone gives the document the additional review step

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-154) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

ADR 0069 left one must point open: authors can record a deviation request in the UI, but they cannot change the text, because the document form has no content editor. A request recorded in the UI therefore produced no deviation and no additional review step.

Two questions follow.

**How does an author change the text?** The server already allows it: `PUT …/draft` takes `content`, and `EditRules` lets the blocks of a locked section change when the section has an active request. The rest of the path was not ready for a UI:

- `values` was required and replaces the entered values, so saving only the content meant sending all values back.
- The endpoint knew no concurrency check. `lockOpenVersion` serializes two saves, but the later one wins: content sent as a whole would silently overwrite what someone saved since the editor opened.
- The live preview of a draft resolves the *template*, not the draft. After a change it would keep showing the standard text, while the PDF and the report show the changed text.
- After a change the only way back was to retype the text. `EditRules` compares blocks exactly, and ProseMirror normalizes runs (ADR 0051), so text that reads the same can still count as changed once the request is withdrawn.

**Does the request alone trigger the step?** The sources disagree:

- Business concept, US-01.1: "Ein Autor kann für einen gesperrten Abschnitt eine Abweichung beantragen; das Dokument erhält dann automatisch den zusätzlichen Prüfschritt der Klasse." The end-to-end scenario, step "Review und Freigabe", says the same of the intention: "Weil die Autorin einen gesperrten Absatz ändern wollte, ist ein zusätzlicher Prüfschritt hinzugekommen."
- Business concept 4.1 ties the step to deviations: "Abweichungen von gesperrten Standardklauseln erfordern einen zusätzlichen Prüfschritt".
- ADR 0022 implemented `requiresExtraReview` as "the report has an authorized deviation", and ADR 0069 stated that a request without a changed text adds no step. Neither weighed that against US-01.1; ADR 0022 only cites it.

## Decision

1. **The request alone gives the document the additional review step.** The acceptance criterion and the scenario are explicit and name the request as the trigger. 4.1 does not contradict them: it says deviations need the step, not that nothing else does. It is also the conservative reading: a wrong "yes" costs one review, a wrong "no" lets a document with an open question about vetted text pass without the department that owns it. ADR 0022 stays in force otherwise: a request still approves nothing.
2. **The decision is stored with the report.** `DeviationReport` gets `requested`: the active requests for locked sections of the template, written as `"requested"` next to `"deviations"` in the stored JSON, only when there are any. `requiresExtraReview` is true if `requested` is not empty, or if the report holds an authorized deviation (reports stored earlier have no `requested`). Frozen versions therefore keep the decision they went through review with, and no migration is needed. An open draft with an older report catches up at its next change, request or at submission, which recomputes the report anyway.
3. **Carried-over requests count too.** A new version that takes over a request (ADR 0022) needs the step again even if the text matches the standard by now. The panel tells the author that withdrawing the request removes the step. Conservative choice, as for point 1.
4. **The editor is the block editor of ADR 0051, for one section.** On a draft, a locked section with an active request gets "Edit text" in the deviations panel, and `BlockList` opens inside the row. It offers everything the blocks of a section can hold, and nothing about the section itself: `EditRules` forbids changing title, position and lock even with a request, so the UI does not offer them. No JSON view. Sections without an active request have no such control and stay read-only; the server decides either way. Editable sections keep having no editor (a separate question, see follow-ups).
5. **A save sends the content on the working state it was made on.** `UpdateDraft.expectedWorkingState` is optional; if the draft has moved on, the save fails with 409. `UpdateDraft.values` becomes optional, so a save of the text leaves values and title alone, and with them whatever the author has typed but not saved in the form. Existing clients send both as before and see no difference.
6. **A concurrent change is resolved by the author.** After a 409 the page refreshes and the text stays in the editor. If only something else changed, saving again is enough. If this section changed, saving is disabled until the author picks "Keep my text" or "Load saved text". Nothing is merged.
7. **The text is never lost by a change of state.** If the request disappears or the version leaves Draft while the editor holds unsaved text, the editor stays open, says why, and refuses to save. Leaving the page asks first (`UnsavedChangesGuard`), and "Submit for review" refuses while the text of a section is unsaved, because submitting would freeze the version without it.
8. **A saved draft has its own preview.** `POST …/versions/{versionId}/preview` resolves the saved content of the draft with the values entered so far. `DocumentWorkspace` uses it for every saved draft; a document not yet created keeps the template preview. Unsaved text of the section editor is not previewed.
9. **The standard text can be restored.** `GET …/versions/{versionId}/standard-text` returns the locked sections of the template, and "Restore standard text" puts them into the editor, to be saved like any other change. This gives the hint at submission ("restore its standard text") a way to be followed.

Both new endpoints live in `DraftContentApi` and need `ReadDocument`. The editor brings ProseMirror along, so it loads when it is opened, as its own chunk; a failed load shows the usual error with retry.

## Alternatives considered

Whether the request alone triggers the step:

| | Request alone triggers it (chosen) | Only a changed text triggers it (ADR 0022 as built) |
|---|---|---|
| Difference | `requiresExtraReview` follows the active requests | `requiresExtraReview` follows the authorized deviations |
| Positive | Matches US-01.1 and the scenario; the step cannot be missed by an author who requests first and changes later; one rule to explain | No review for a request that came to nothing |
| Negative | A forgotten request costs a review; the author has to withdraw it | Contradicts the acceptance criterion; the panel has to explain why a request changes nothing |

Where the decision is kept:

| | `requested` in the stored report (chosen) | Computed from the request rows | New column (V48) |
|---|---|---|---|
| Difference | Additive key in `deviation_report` | `requiresExtraReview` reads `document_deviation_request` | `document_version.extra_review_required` |
| Positive | Frozen by the existing guard; old reports stay valid; no migration | No stored redundancy | Plain boolean in SQL |
| Negative | The report grows beyond changes; `ContentCodec` stays the only reader | Every reader needs the rows; already approved versions would change their answer | Migration and guard change for what the report already holds |

Where the editor sits and how much it edits:

| | Section in the deviations panel, whole blocks (chosen) | Text only, no structure | Content editor for the whole document in the form |
|---|---|---|---|
| Difference | `BlockList` per section with a request | Only headings and paragraphs editable | All sections in one editor |
| Positive | Reuses ADR 0051 as it is; the report and `EditRules` already cover every block type; the task of the author stays small | Simpler for authors | Also solves editable sections |
| Negative | Authors see forms for conditions and repeats when a locked section has them | A section with a table or list could not deviate in the UI; needs a new editor mode | Decides how authors edit all content, which nobody asked for here; bigger risk for sections without a request |

How a stale save is caught:

| | `expectedWorkingState` on `PUT …/draft`, whole content (chosen) | New endpoint per section | `If-Match` with an ETag |
|---|---|---|---|
| Difference | One optional field; the client replaces its section in the content | `PUT …/sections/{id}` merges on the server | Header on the same endpoint |
| Positive | Smallest change; one save path, so one set of rules | Sends less; other sections cannot be touched by the client | Standard HTTP |
| Negative | The client sends the whole content; any save in between means a retry, even of another section | Second save path with its own audit and rules | The API has no ETags on JSON resources; generated clients handle headers worse than a body field |

## Consequences

### Positive

- The deviation flow can be finished in the UI: request, edit, report, additional step. US-01.1 is met.
- Stale saves of content no longer overwrite silently, for every client that sends the working state.
- The live preview of a draft shows what the draft says, also for content changed through the API.
- Reviewers see why the step is there when the text did not change: the request row says so.

### Negative / Cost

- Behaviour change for API clients: `requiresExtraReview` is true right after a request, also on `DeviationsView` and in outbox events.
- A version whose request came to nothing goes through the step unless the author withdraws the request.
- An older open draft with an active request but no change shows `requiresExtraReview` false until it is next saved or submitted; the submission decides correctly.
- A save of the form between loading and saving the section costs the section one retry when the refresh has not arrived yet.
- The form itself still saves without `expectedWorkingState`, so two people changing values overwrite each other as before.
- Unsaved text of the section is not in the preview; authors see it after saving.
- A section with an unauthorized deviation (request withdrawn after a change) needs a new request before the standard text can be restored in the UI.
- Editing with the template's block editor shows designer terms to authors for rare blocks (conditions, repeats, clause references).
- A placeholder that the content does not know is only refused on save, as one text: the check endpoint with locations is for designers (ADR 0051).

## Follow-ups

- Must be done: check the flow in a browser against a running stack (ProseMirror in a real browser, screen reader pass); the automated tests run in jsdom and against the API.
- Should be decided: whether authors get an editor for editable sections; the business concept has it, the form does not.
- Should be done: show the issues of a refused save at the block, with an author-facing check endpoint.
- Should be done: send `expectedWorkingState` from the form as well.
- Could be done: restore the standard text directly from a section without a request; live preview of unsaved section text.
- Ruled out: merging concurrent changes of one section; a request that approves a change by itself (ADR 0022).

## Reversibility

**high**: Rationale: no migration. Going back to "only changed text" is one line in `DeviationReport.requiresExtraReview`, and the stored `requested` key is ignored by a reader that does not know it. The editor is additive in the frontend; `expectedWorkingState`, optional `values` and the two read endpoints are additive in the API. Versions submitted in between keep the answer stored in their report.
