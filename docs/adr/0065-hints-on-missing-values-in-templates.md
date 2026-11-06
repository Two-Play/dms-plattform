# 0065: Hints on missing values: test mode and preview report them, computed fields get a label, the approval block gets its own code

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06; proposed 2026-11-05, implemented in DMS-85) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

Since DMS-83 (ADR 0020, addendum) a computed field that reads a missing value has no value, instead of `0`. A template that relied on `0` for an empty optional field now blocks submission and approval (ADR 0062) until a designer adds a `present_fields` check. The designer learns of this only when an author is refused. Conditions were left as they were: they still read a missing value as empty, so a condition can take a branch for a reason nobody intended, and nothing says so.

DMS-85 asks for four things: test mode points to computed fields without value and suggests a guard; conditions that depend on a missing value are reported in test mode and preview; computed fields get a label, so hints do not show the technical key; the approval block gets its own error code that names the computed field.

## Decision

1. **A computed field can have a label.** `ComputedField.label` is optional and at most 200 characters; a blank label is refused on save. Content without a label shows the key (`displayLabel`). The label sits in the existing `computed` entry of the content, so the stored format stays `1` and needs no migration. Like a field label, it has one language.
2. **The resolver reports conditions that depend on a missing value.** After a condition is decided, and only if it mentions a field or computed field without value, it is evaluated once more with those values as CEL unknowns, the mechanism computed fields use. If the result is unknown, the missing value decides it (`Resolution.conditionsOnMissing`). The branch taken does not change: conditions still read missing values as empty. The second evaluation only informs: if it fails, the names the expression mentions count, and a resolution never fails because of it.
3. **Test mode reports per run.** `TestRun.hints` has one entry per computed field and per condition, with the labels of the fields, the test cases that showed it, and for computed fields the guard. Hints do not block anything, neither the step to `Testing` nor the release.
4. **Only optional fields get a guard.** A required field has to be filled in anyway, and a guard would hide that it is missing.
5. **A suggestion is offered only if it is honest and compiles.** `guard ? calculation : fallback` with `0.0`, `0`, `0u`, `''`, `false` or `[]` by result type. Dates, amounts and results of unknown type (`dyn`) get the guard alone: any date or currency would be a value made up for a document, which is what DMS-83 removed. The template is compiled with the suggestion before it is offered, so a suggestion over the length limit is left out too. The UI asks the designer to replace the fallback.
6. **The preview has its own list.** `PreviewView.conditionHints`, not `issues`: an issue says a value was refused or the template failed, and the frontend shows such general issues as an alert. Fields the platform sets are left out, as the author can do nothing about them. Required fields count: their condition can change as well. So does a value the preview refused, as it is left out and the condition reads the field as empty. The form shows each field once as a link to its input, not the condition, which is meant for designers.
7. **The approval block has the issue code `computed_field_missing`.** `IssueKind.ComputedFieldMissing` lists the computed fields, with key and label; `IssueView.computedFields` carries them. `field` stays the input the author has to fill in, as the form marks that one. The response code stays `incomplete` with the same HTTP status, as ADR 0062 decision 5 promises: one refusal lists everything. A field the class requires anyway keeps `missing_required`, since one issue per field is the rule and filling it in solves both.
8. **Messages are composed where they are read.** Test mode and preview return data (labels, guard, suggestion), and the frontend writes the sentences through the i18n catalogs in German and English. For `computed_field_missing` the frontend also uses its own sentence; the English `message` stays for API clients and logs.

Repeat collections also read missing values as empty, but they are not reported: DMS-85 names conditions, and a repeat over a missing list shows no rows, which the preview makes visible.

## Alternatives considered

Finding conditions that depend on a missing value:

| | Evaluate again with unknowns (chosen) | Static: the expression mentions the field | Evaluate with other values and compare |
|---|---|---|---|
| Difference | Asks CEL whether the value decides the result | Reads identifiers only | Tries candidate values for each missing field |
| `'x' in present_fields && x > 0.0` | No hint, correct | Hint, wrong: exactly the designer's fix would stay flagged | No hint |
| `x > 0.0 \|\| termType == 'fixed'` with the right side true | No hint, correct | Hint, wrong | No hint |
| Cost | One more evaluation, only if a value is missing | None | Many evaluations, and it depends on the candidates |
| Positive | Same rule as computed fields, exact | Simplest | Works without unknowns |
| Negative | Two evaluations per condition with missing values | Noise teaches people to ignore hints | May miss a value that changes the result |

Conditions themselves:

| | Keep empty values, report (chosen) | Make conditions unknown as well | Refuse such conditions on save |
|---|---|---|---|
| Difference | Behaviour stays, a hint is added | A condition without value picks no branch | Requires a guard in every condition on an optional field |
| Positive | Existing templates choose the same branches | Strict and uniform | No hidden dependency |
| Negative | The condition still decides on an empty value | Needs a rule for "no branch"; released versions would render differently, which ADR 0020 rules out | Released versions cannot change; most conditions on optional fields are intended |

Where the approval block shows:

| | Issue code, response code stays (chosen) | New response code | Issue per computed field |
|---|---|---|---|
| Difference | The input stays `field`, computed fields are named in the issue | `computed_incomplete` instead of `incomplete` | `field` is the computed field |
| Positive | The form keeps marking the input; one refusal lists everything | Easy to tell apart | One issue says one thing |
| Negative | A required field that also feeds a computed field does not name it | A refusal with both kinds of issue has no single code; breaks ADR 0062 decision 5 | The form has no input for a computed field; authors cannot act on it |

Where the hints are worded:

| | Data from the backend, wording in the frontend (chosen) | Wording in the backend |
|---|---|---|
| Positive | Follows the UI language and the i18n catalogs | One text for all clients |
| Negative | API clients write their own text | The backend does not know the UI language; the text of the test mode depends on the document language |

Other options considered:

- **Blocking the release while hints exist:** rejected. A designer may want a computed field to require its input; then the block at submission is correct.
- **Label required for new computed fields:** rejected. The fallback covers it, and a required label would refuse a new version of an existing template on save.
- **A new content format for the label:** not needed; the field is optional and older content decodes unchanged.

## Consequences

### Positive

- Designers see in test mode what would otherwise reach them as a refused submission, with a guard they can paste.
- Following the suggestion removes the hint, which `TestHintsSpec` proves.
- Authors see which fields can still change the text before they submit.
- Clients can tell a field the class requires from a field a computed field needs.
- Existing templates work as before: no migration, the same branches, the key where there is no label.

### Negative / Cost

- A condition that reads a missing value is evaluated twice. Only then: a filled-in document pays nothing.
- A `missing_required` issue no longer stands for the fields computed fields need. `computed_field_missing` replaces it, so a client that matched on the code for these fields has to add the new one. The frontend does not match on it.
- A number fallback of `0.0` brings back the `0` of DMS-83 if a designer takes it over unchanged. It is then a decision of the designer, and the UI says so.
- While an author fills in a long form, the hint in the preview lists many fields. It is a neutral note below the required values, with no alert.
- Released versions get labels only with a new version, as their content never changes.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Must be done: names of the generated test cases come from the backend in English, also in the German UI, and now also appear in hints. They should become codes the frontend translates.
- Could be done: a button that takes a suggestion over into the calculation.
- Could be done: a hint for repeats over missing lists, if designers ask for it.
- Could be done: a guard for `dyn` results by the type of the value in the typical test case (`monthlyFee.amount * monthlyFee.amount`).
- Ruled out: changing which branch a condition takes on a missing value (ADR 0020, addendum DMS-83).

## Reversibility

**high**: No migration. Removing `noteMissing`, `TestHints` and the new view fields restores the behaviour before; `computed_field_missing` can go back to `missing_required` in one place. Stored labels can stay: the decoder ignores unknown fields, so older code reads such content.
