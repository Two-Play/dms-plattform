# 0064: Gaps of failure notices: whoever is covered or named reaches the failure, deputies are told, unread notices are reminded of, a dead publication is audited, every code has a reason

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06; proposed 2026-11-05, implemented in DMS-146) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

DMS-146 collects what ADR 0058 left open. Each point comes from the code as it stood:

- **Records managers with a restricted role.** `recordsManagers` keeps assignments without unit and class, and the failure lists check `Retry…` on `Resource(tenant)`, which a restricted role does not cover. Such a person may retry a version in their scope (`Policy` decides per document), but is never told and has no list. The legal holds page stays closed to them (ADR 0047).
- **Deputies.** Failure notices go to the owner only. Deputies hear of review deadlines of the owner (ADR 0057), not of a failed job.
- **Reminder.** A notice nobody reads ends the matter. The version stays in approval or approved. The business concept (8.3) has reminders for what stays open: due date, +3, +7 days, configurable.
- **Audit.** Renditions record each failed attempt (ADR 0055), publications nothing. The audit chain is computed in Scala (ADR 0017), so the trigger of ADR 0058 cannot write the entry.
- **The named person.** ADR 0055 asks for the owner role on top of being named. With a confidential document the named person may be the only one cleared: `clearedFor` lets owner and author pass whatever their groups, and records managers and administrators outside the allowed group are denied. Nobody could act. Also, someone without an author or owner role cannot read a version in approval, so the document page is closed and a retry right alone would have no place. The notice also promised the retry to records managers who are not cleared.
- **Failure codes.** All codes of `RenderingError` and `PublicationJobs.jobError` have a text. What had none: a defect in a handler (`Workers.guarded` writes the first line of the cause) and the checks at the start of the handlers (no tenant, bad payload), which arrive without code. `parseJobError` did not know `timeout`, so the status said "cause unknown" where the notice named it.

## Decision

1. **Records managers are told by coverage.** Everyone whose active assignment covers the document (`Policy.rolesInScope`) is a recipient. Tenant-wide ones keep audience `records`. Restricted ones get the new audience `scoped`. Tenant administrators stand in only if no records manager covers the document.
2. **The failure lists are filtered instead of refused.** `GET …/failures` no longer answers 403. Tenant-wide records managers and tenant administrators see everything as before. Everyone else sees the rows they could retry but for confidentiality: restricted roles see what their role covers, the named person what they are named for. Rows the caller may not retry have no title, as before. For anyone else the list is empty. A new page `/failures` shows both lists; records managers with a restricted role only find it in the navigation, the named person gets it from the notice.
3. **Deputies are told and decide nothing.** The active deputies of the person who answers for the version get a notice with `actingFor`. As in ADR 0057 the deputyship must cover the role and the deputy must still hold it and be cleared. For an owner that is `Owner`, for an author of a document without owner `Author` (`ApprovalWorkflow.responsibleDeputies`). Their retry right is their own: owner decisions are not forwarded (ADR 0028, 0030). If they may neither read nor retry, the notice has no link.
4. **Unread notices are reminded of.** The job `notifications.failure-reminder-check` runs hourly per tenant. While a version waits for a dead job, one event `document.failure-reminder` is written per reminder day that has come, by default day 3 and day 7 after the death (`dms.notifications.failureReminderDays`; empty switches it off). Delivery builds the recipients as for the first notice and leaves out whoever has read a notice of this failure, first notice or reminder. Someone new, such as a new owner, is told here for the first time. Migration V44 records each round per job and moment of death, under a primary key, so repeated checks and several instances send it once. Missed days are not caught up. A retry stops the reminders; a new death starts them anew. Same kind and category as the first notice: subject "Erinnerung: …", first sentence "Seit … noch offen".
5. **A dead publication gets an audit entry.** A new outbox subscriber writes `document.publication-failed` for `document.publication-dead`: technical actor `dms-publication`, version, job, attempts and the code, not the error text (ADR 0058). The relay delivers at least once, so the entry carries the event id and a repeated delivery adds none. The subscriber comes first, so the failure of another subscriber does not delay the entry.
6. **Being named is enough to retry.** `RetryRendition` and `RetryPublication` no longer ask the person named owner (without owner: the author) for the owner or author role. This changes ADR 0055. The first rule of `Policy.decide` stays: some active role must cover the document, so a person with no role left cannot act. Confidentiality does not stop them, as they pass it anyway. The status endpoints still need `ReadDocument`; the person who cannot read has the list.
7. **Texts promise only what is possible.** Notices now carry `canRead`, and failure notices decide with the person's access groups (`PlatformNotificationEvents` gets `Groups`). Someone not cleared in a list audience reads that the owner can retry. Someone who can retry but not read goes to `/failures`.
8. **Every code has a reason.** A defect becomes `[internal] …` in `Workers.guarded`. The handlers' own checks become `[invalid] …`. `parseJobError` knows `timeout` like `dms.job_failure_code`. The reasons are one table, `NotificationTemplates.FailureReasons`, and a test compares it with what the platform emits. The fallback, for an old event or a code of a newer instance, no longer says the reason is unknown but says where the technical message is. The interface has texts for `timeout` and `internal`.

Flyway V44. No new dependency.

## Alternatives considered

| Topic | Option | Difference | Positive | Negative |
|---|---|---|---|---|
| Reaching the failures of a restricted role | **Filtered list on a page of its own (chosen)** | Same endpoints, `/failures` | Works for restricted roles and named people alike; no new permission | 200 rows are cut before the filter; the endpoints no longer say 403 |
| | Open the legal holds page to them | Change the page | No new page | ADR 0047 keeps that page tenant-wide; holds would need hiding |
| | Link only to the document page | No list | Nothing to build | A records manager cannot open a version in approval (ADR 0058) |
| Named person without read permission | **Retry from the list (chosen)** | Reading stays as it is | No content shown to someone who may not read it | Their way in is the link in the notice |
| | Open the status endpoint and the document page | Wider `ReadDocument` | One place for everybody | A retry right would grant reading content of a version in approval |
| Who may retry | **Named person with any covering role (chosen)** | Role no longer asked for | The only cleared person can act; `decide` stays one rule | A named person with only `Reader` can requeue a job, which changes only its schedule |
| | Also without any role | Exception in `decide` | Covers someone whose roles all ended | Someone who left could act; deny by default is broken |
| | Keep ADR 0055, tell administrators to change the owner | No code | Nothing changes | A confidential document can be stuck, and the fix depends on someone who is not cleared |
| | Let deputies retry too | Deputy acts for the owner | Faster during absence | Goes against ADR 0028 and 0030; deserves a decision of its own |
| Who gets a reminder | **Who has not read a notice, while the failure is open (chosen)** | Filter at delivery | What the ticket asks for; no reminder to someone who knows | Everybody read it and nobody acted: silence |
| | Everyone while the failure is open | No filter | Also covers read and not acted on | Reminds those who are already at it, by mail too |
| | A separate escalation | New audience | Answers "read but not handled" | Needs someone to escalate to; not asked for yet |
| Remembering a reminder | **Table per job, death and round (chosen)** | V44 | States the fact, works without lookback; the way of `dms.deputyship_notice` and ADR 0057 | One more table |
| | Idempotency key of `dms.job` | No migration | Less code | Job rows become the business record (rejected in ADR 0028 addendum) |
| | Search `dms.outbox_event` | No migration | Less code | Ties the check to outbox retention |
| Audit entry | **Outbox subscriber (chosen)** | After commit, at least once | Covers every way a job dies | A short delay; not in the transaction of the death |
| | In the trigger | SQL | Atomic | Would need the hash chain in SQL as a second implementation |
| | In the handler on the last attempt | Scala | Simple | Misses timeout and crash (ADR 0058) |
| Reason of a defect | **`[internal]` in `Workers.guarded` (chosen)** | One place | Every kind of job; visible in the status too | All job kinds get the prefix in `last_error` |
| | `internal` as default in `dms.job_failure_code` | Migration | No code in Scala | Hides a forgotten code as an internal error; the status would still have none |

## Consequences

Positive:

- Whoever a failure concerns hears of it and can reach it, however their role is restricted.
- A confidential document no longer depends on someone who is not cleared.
- A failure nobody looked at comes back twice, and the record shows that a publication died.
- A notice names a reason for every code. The test fails if a new code has no text.

Negative:

- More mail per failure: deputies, restricted records managers, and up to two reminders.
- Anyone can call the lists; each call reads the documents of up to 200 failures. Only failures of the tenant are read, and the answer is filtered. A tenant with more than 200 open failures could hide rows from a restricted caller.
- The title link in the list leads someone who may not read to a refused page, as it always did for records managers.
- A person named owner whose roles have all ended still cannot act, and the text sends them to records management. If nobody is cleared then, the owner has to be changed or a role granted.
- Reading a mail is not reading in the app. Whoever reads mails only keeps getting the reminders, and they stop after two.
- If everybody has read a notice and nobody acts, nothing follows.
- A dead rendition has no entry of its own: its attempts are recorded, a timeout or crash on the last attempt is not.
- `failure.rendition` of a document with deputies reads the workflow cases, one more query per dead job.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: an escalation when a failure stays unhandled although it was read (ADR 0058 follow-up "reminder when a failed job stays unhandled for days", the part this ADR leaves open).
- Should: audit entry for a dead rendition on the paths the handler does not see (timeout, crash).
- Later: should a deputy of the owner be able to retry? A decision for ADR 0028, 0030.
- Later: move the filter of the lists into SQL if tenants get many open failures.
- Ruled out: error texts in audit entries, events or notifications.

## Reversibility

**high**: Filtered lists, deputies, reminders and texts are code. Dropping the job leaves a table of records that nothing reads. The rule of who may retry is one line in `Policy`. Audit entries stay, as they should; removing the subscriber only stops new ones.
