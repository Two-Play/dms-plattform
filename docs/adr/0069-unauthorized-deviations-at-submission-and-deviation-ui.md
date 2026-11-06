# 0069: Unauthorized deviations get their own error code at submission; the deviation UI shows requests and the report, not an editor

| Field | Value |
|---|---|
| Status | Proposed (2026-11-05, implemented in DMS-84); point 8 and the first follow-up are superseded by [0071](0071-editing-locked-text-and-extra-review-on-request.md) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

ADR 0022 stored deviation requests and the deviation report, but only the API reached them. DMS-84 brings them to the UI: locked sections are recognizable in a draft, authors request and withdraw deviations, reviewers read the report (location, change, reason, authorized or not), and unauthorized deviations at submission return their own error code with the affected sections.

Until now submission failed with `DocumentError.Invalid` and the response code `invalid`, the same as every other invalid request, with the English reasons joined into one message. The frontend could only append a general hint to any `invalid` response, could not name the sections, and could not translate them.

## Decision

1. **Submission fails with `DocumentError.UnauthorizedDeviations`**, one entry per `EditViolation` with the title of its section, from the document or else the template. The response is 422 with the code `unauthorized_deviations`.
2. **The sections travel in `ApiError.issues`.** `field` is the section id, `label` its title, `code` the kind of violation, `message` the English reason. `EditViolation.code` provides the stable codes (`locked_section_changed`, `locked_section_removed`, ...). The frontend writes its own sentence per code through the i18n catalogs and falls back to `message` for a code it does not know.
3. **Every edit rule violation counts, not only changed text.** They all come from the same check and each names a section. The code of the issue keeps them apart.
4. **Saving a draft stays as it is.** `updateDraft` refuses content that breaks the edit rules with `invalid`, as the acceptance criterion names submission only and the UI sends no content when it saves.
5. **The panel sits on the document page, below the form or the content.** It lists the locked sections of a draft with request and withdraw actions; for any other version it shows the requests and the report as stored. File documents have no panel, as their content is the uploaded file. The preview also labels locked sections, through a rule of the frontend, so the shared document stylesheet and with it the print stay unmarked.
6. **No new permission flag.** `ReadDocument` and `EditDraft` on a draft ask for the same roles in the same scope after the same confidentiality check (`Policy`), so whoever sees the open draft may act on it. The form of the draft already relies on this. The backend checks every action as before.
7. **No step to approve or refuse a request.** ADR 0022 rules out requests that approve a change by themselves: the additional review step decides. The UI therefore knows two states of a request, active and withdrawn, with who and when.
8. **The UI does not edit locked text.** No acceptance criterion of DMS-84 asks for it and the document form has no content editor at all. The panel says so, so nobody expects a request alone to change the document. A request without a changed text produces no deviation and no additional review step (ADR 0022).

## Alternatives considered

How the sections reach the client:

| | `issues` of `ApiError` (chosen) | New field `sections` on `ApiError` | Parse the message |
|---|---|---|---|
| Difference | Reuses the list with `field`, `label`, `code`, `message` | Own type for sections | No API change |
| Positive | No new schema; the frontend reads issues already | `field` does not carry a section id | Nothing to build |
| Negative | `field` means a section here, so clients must check the response code first | A second list on every error type, for one case | Fragile, not translatable |

Which refusals get the code:

| | Submission only (chosen) | Submission and saving a draft |
|---|---|---|
| Difference | `updateDraft` keeps `invalid` | Both use `unauthorized_deviations` |
| Positive | What the criterion asks; other refusals of content, such as computed fields, stay in one response | One code for the same rule |
| Negative | The same rule has two codes, depending on where it fails | Mixed content problems need splitting; no client needs it, as the UI sends no content |

Where permission for the actions comes from:

| | Status of the shown version (chosen) | New `canEditDraft` on the document view |
|---|---|---|
| Difference | The UI offers actions on an open draft it can read | The backend evaluates `EditDraft` for the view |
| Positive | No backend change | Survives a later split of the two rules |
| Negative | A later difference between reading and editing a draft must add the flag | Always the same answer as reading today |

## Consequences

### Positive

- Authors see which sections block the submission, in their language, and reach the section with one click or key press.
- Reviewers read the report and the reasons on the page they vote from.
- `invalid` no longer needs a hint about locked sections, which was misleading for every other invalid request.

### Negative / Cost

- API clients that matched `invalid` for this refusal have to match `unauthorized_deviations`. The status code stays 422.
- An author can record a request in the UI, but cannot make the change it permits there: changing the text still needs the API.
- The location in the report is built from the path in the frontend, in parallel to `DeviationLocation.show` for API clients.

## Follow-ups

- Decided in ADR 0071: how authors change the text of a locked section with an active request in the UI (reuse of the visual content editor of ADR 0051).
- Could be done: show the report in the vote dialog as well, and mark requested sections in the rendered content of a version in review.

## Reversibility

**high**: Rationale: no migration. Going back to `invalid` is a change of the error mapping; the panel is additive and can be removed without touching stored data.
