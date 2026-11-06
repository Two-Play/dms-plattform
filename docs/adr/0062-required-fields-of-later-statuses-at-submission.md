# 0062: Fields needed in a later status are checked at submission

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06; proposed 2026-11-04, implemented in DMS-147) |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

ADR 0019 makes requiredness depend on the status: a class names the statuses in `requiredIn`, and each status change validates the values against its target. Values freeze at submission, though. After `Draft` nobody can add a value unless the version goes back to `Draft`.

So a field required only in `Approved`, `Published` or later is checked at a moment when the author can no longer react:

- Required in `Published`: the approval succeeds, the publication job fails with `[invalid]` on every attempt. ADR 0061 added a way out (withdrawal after the job is dead), which heals the result, not the cause.
- Required in `Approved`: the check runs in `ApprovalWorkflow.completeApproval`, inside the rendering job and after the last vote. The job fails for good, the version stays `InApproval`, the case stays `Rendering`. Neither a retry nor a withdrawal helps (ADR 0061, decision 13). DMS-147 asked whether there is a way out: there is none.
- Required in `ReviewDue` or `Superseded`: the version becomes valid, but the deadline job or the publication of its successor fails on it later.
- A field a computed field reads (DMS-83) counts as required for approval and ends the same way as the `Approved` case.

## Decision

1. **Submission checks what the version will need.** `Validation.checkSubmission` validates against `InReview` as before, and also reports every empty field required in a later status of the regular path: `InApproval`, `Approved`, `Published`, `ReviewDue`, `Superseded`, `Archived` (`Requiredness.afterReview`).
2. **Only fields an author can fill in.** Fields of origin `System` are skipped: the platform sets them later (`renditionChecksum`).
3. **Inputs of computed fields too.** `missingComputedInputs` also runs at submission. The check at approval stays for versions already under way.
4. **`requiredIn` keeps its meaning.** It still says in which statuses a value must exist, and the later checks stay. No class changes, no migration. In practice, a required status after the review now means "required at submission".
5. **One answer with everything.** The refusal stays `Incomplete(InReview, issues)` with HTTP status and code unchanged. Each issue names the first status that needs the field, once per field, the current status first. The workspace already lists these issues and marks the fields.
6. **The form says so in advance.** `ProfileSchema.isRequired` also marks fields required after the review, so the form and the preview show them as required before the author submits.
7. **Drafts under way need nothing special.** They meet the check at their next submission and can be corrected there. A version returned to `Draft` is checked again. Versions already past `Draft` are not touched.

## Alternatives considered

| Option | Difference | Positive | Negative |
|---|---|---|---|
| **Check at submission (chosen)** | Moves the effective date of later requiredness forward | The author can still correct; closes the cause for every class; small change | A class can no longer express "may stay empty until approval" for an author's field |
| Check only `Published` (proposal in DMS-147) | Leaves out `Approved` and the statuses after publication | Smaller rule | `Approved` is the worse case: it has no way out. `ReviewDue` and `Superseded` would fail later |
| Check at approval | Validates against `Published` before the last step | Exact, values are frozen | Runs in the rendering job, makes that job fail for good (ADR 0061, decision 13) |
| Check at the last vote, before rendering | Refuses the vote | No dead job | Approvers cannot fix a field; the case can only be rejected, after a full review |
| Warn at submission | Author may go on | Keeps the old freedom | The warning describes a version that can never be published; nobody wants that outcome |
| Allow edits after submission | Late values for such fields | Keeps late requiredness meaningful | Breaks the frozen package that reviewers and approvers decided on (ADR 0025) |
| Refuse such class settings | Validates `requiredIn` at class level | Clear for admins | Existing class versions stay; refuses settings that work with the chosen rule |

## Consequences

Positive:

- A version an author can submit no longer fails later because one of the author's fields is empty.
- The author learns of it at the one moment a fix is cheap, and the form shows it earlier.
- ADR 0061's way out stays for other causes, such as `[conflict]` and `[legal_hold]`.

Negative:

- Requiredness after the review takes effect earlier than the class setting says. An administrator reading `requiredIn` must know this rule. The class editor does not explain it yet.
- A class that expected a field to be filled in between submission and publication now fails at submission. No such path exists today, as values cannot be changed then.
- Each submission loads the template to check computed fields, one read more than before.

Not covered:

- Versions already in `InReview`, `InApproval` or `Approved` at rollout. There is no production data yet. In any other environment, a version in `InApproval` with such a gap still has no way out.
- A `System` field a class requires but the platform does not set, such as `documentNumber` in a class without a number range.
- Statuses outside the regular path. A field required in `Withdrawn` can still block withdrawing a published version.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Done in DMS-148 (ADR 0063): `System` fields, `Withdrawn`, a revision in another validity scope, approvers of a dead publication, task chain in the evidence report, and the rule in the API description (the interface has no class editor).

## Reversibility

High. No migration and no stored data: removing the call in `transition` and the union in `ProfileSchema.isRequired` restores the old behaviour. Versions submitted in the meantime only have more values than needed.
