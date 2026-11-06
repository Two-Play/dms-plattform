# 0080: A violation of the audit chain is told once with reminders while it stays open, the cockpit shows the last verification and orphaned documents

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-169) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

DMS-169 takes two should points of ADR 0077. Each was checked against the code first.

1. **A finding reaches the log only.** `audit.verify` runs per tenant and day and records every run in `dms.audit_verification_run` (ADR 0077). A violation is an error in the log and a note in each later report. Nobody is told in the product, and nothing shows whether the check ran at all.
2. **"Without owner" is not "orphaned".** The cockpit counts documents with no owner set. The business concept (6.2, "Owner-Verlust") means something else: the owner leaves or changes function and the document is marked "verwaist".

Given:

- The verification stops at the first violation, so a run names one. It runs again every day, and until the chain is repaired it finds the same thing. Reports that fall back also run `verifyFully` and record a run.
- `AuditCheckpoints.compare` reports a checkpoint it cannot read at the *head* of the chain, so the position of that violation moves when the chain grows. A rule "same position, same violation" would send a notice every day.
- `FailureFollowUps` (ADR 0064) has the pattern for reminders: days +3 and +7 (business concept 8.3), one row per round under a primary key, missed days not caught up, those who read a notice left out. The notice machinery delivers in-app always, by mail per category (ADR 0052), and reads recipients on delivery.
- There is no metrics library and no metrics endpoint in the platform. `GET /ready` (ADR 0056) answers without sign-in, names components and nothing about them, and tells a load balancer whether the instance should get traffic. DMS-137 is to bring the first metric.
- `dms.document.owner_id` is a person; role assignments have a validity and a scope of unit and class. Nothing marks a person as gone: the directory is another system (ADR 0037).

## Decision

### Telling people

1. **What a violation is.** The same kind at the same position, in an unbroken series of runs. For `CheckpointMismatch` the kind alone, as its position moves with the chain. It is named by the first run of the series. An intact run ends it; found again, it is a new violation with a new notice.
2. **The first notice goes out with the run, in its transaction.** `AuditEvidenceLive.verifyFully` writes the run, the row in `dms.audit_violation_notice` (V57) and the outbox event `audit.chain-violated` together, whatever started the run. So a report that finds a violation first does not wait for the next night, and a crash cannot leave a run without its notice. An advisory lock per tenant, taken before the run is written, makes two runs at once agree on which one was first.
3. **Reminders come from the daily job.** After a run that still finds the violation, `audit.verify` sends the reminder that is due: `dms.audit.violationReminderDays`, default 3 and 7, empty for none. Days count from the first notice, in calendar days (UTC), as the job does not run at the second of the notice. Counting from the notice also means a violation older than this feature reaches people first as a notice. Missed days are not caught up (ADR 0064). Only while the newest run finds it: the job needs no check of its own, and the day it does not run nobody can say the violation is still open.
4. **Who.** Every active compliance officer, whatever the scope, and the tenant-wide administrators (`tenantAdministrators`, as elsewhere). The chain is one per tenant, so scope has no meaning here. Someone who holds both gets the officer's text. Who is told is decided on delivery, so a new officer gets the next reminder.
5. **Everyone is reminded,** not only those who have not read a notice. This differs from ADR 0064 on purpose: there a reader is someone who can retry; here reading closes nothing and only a run does. At most two more notices.
6. **What travels.** The date of the finding and the round. Not position, kind or detail, in the event, in the table or in the text: nothing of the audit trail goes with a notice, and a mail leaves the platform. Fixed text in `de` and `en`, kind `audit.chain-violation`, new category `audit-integrity`, mail on by default. The link leads officers to the cockpit; administrators cannot open it, so they get no link and are told to speak to the compliance officers.
7. **No metric, no health signal.** The platform has neither a metrics library nor an endpoint, and `/ready` is the wrong place: it is public, it would tell anyone that a chain is violated, and a violation says nothing about whether an instance should get traffic. The error log stays as the signal for operations; the production checklist alerts on `level=ERROR`. A metric follows once DMS-137 brings the mechanism.

### Cockpit

8. **The last verification.** `GET /api/v1/compliance/audit-verification`: when, intact or violated, entries checked, kind and position of a violation, since when, and whether the checkpoints could be compared. Never the detail. A card of its own above the figures, on its own query, so that a finding stays visible if the figures cannot be loaded.
9. **Never ran and stale.** No run: said so, as nothing is known. A run at least `AuditEvidenceLive.PrefixMaxAge` (3 days) old is stale, the same limit after which a report no longer relies on it. Staleness is a flag beside the result, so a violated chain stays violated when the job stops. Any full run counts, also one of a report: what matters is when the chain was last read as a whole.
10. **Rights.** The compliance officer role, any scope (`requireOfficer`). No document can be concluded from a position in the chain, so chapter 16 is kept.

### Orphaned documents

11. **The figure.** A document with a valid version and an owner who holds no active `Owner` assignment that covers the document (`Policy.rolesInScope`, the rule of every decision on the document). A role for another unit does not count: the concept names a change of function. A deputy does not count: owner decisions are not forwarded (ADR 0030). Documents without owner stay a figure of their own, so a document is in one of the two.
12. **List and export.** `GET /api/v1/compliance/orphaned-documents` with unit and owner, export `orphaned-documents` through `POST /exports/{list}` with the owner's id and name. Same function as every other list: officer role, `MonitorCompliance`, confidentiality, audited export. Confidential documents the officer is not cleared for are not counted.

Flyway V57. No new dependency.

## Alternatives considered

| Criterion (what makes a violation the same) | Chosen: kind and position, for checkpoints the kind | Kind and position | First violation since the last intact run | Hash of the detail |
|---|---|---|---|---|
| A moving checkpoint position sends a notice daily | No | Yes | No | Yes: the detail names it |
| A second violation at another position is told | Yes | Yes | No, until the first is gone | Yes |
| A chain that is damaged further shows up | Yes, except behind a checkpoint mismatch | Yes | No | Yes |
| Depends on text of the detail | No | No | No | Yes |

| Criterion (where the first notice is written) | Chosen: in the transaction of the run | In the job, after the run |
|---|---|---|
| A crash between run and notice | Impossible | The retry of the job covers it, up to 3 attempts |
| A finding of a report | Told at once | Told the next night |
| Change to `verifyFully` | One lock and a call | None |

| Criterion (when reminders are checked) | Chosen: in the daily job | A check of its own, hourly (ADR 0064) |
|---|---|---|
| New job and schedule | No | Yes |
| Precision | The day it is due, after the night's run | Within the hour |
| Knows the violation is still open | Yes, a run just found it | Only as of the last run |

| Criterion (who gets a reminder) | Chosen: everyone | Who has not read a notice (ADR 0064) |
|---|---|---|
| Fits what ends the matter | Yes: only an intact run does | No: reading ends nothing |
| Mail to those already at it | Yes, twice at most | No |
| Silence if all read and nobody acts | No | Yes |
| Code | None | A query over notifications per kind |

| Criterion (the category) | Chosen: `audit-integrity` | `processing-failure` | Mail that cannot be switched off |
|---|---|---|---|
| Fits what the settings page says | Yes | No | Yes |
| Someone can opt out of the mail | Yes; in-app stays | Yes, together with failed jobs | No |
| Exception in the delivery | No | No | Yes |

| Criterion (signal for operations) | Chosen: the error log | Component in `/ready` | ZIO counter without exporter |
|---|---|---|---|
| Reachable by an alert | Through the log pipeline | Yes | No |
| Tells anonymous callers about a violation | No | Yes | No |
| Mixes up health of the instance and of a tenant's data | No | Yes | No |
| Cost per request | None | A query per tenant | None |

| Criterion (what "active owner role" means) | Chosen: active and covers the document | Active, any scope | Also asks the directory |
|---|---|---|---|
| Finds a change of function | Yes | No | Yes |
| Same rule as a decision on the document | Yes | No | Yes |
| Finds someone who left with a role still open | No | No | Yes |
| Depends on another system | No | No | Yes: the figure fails or lies when it is down |

| Criterion (cockpit data) | Chosen: endpoint of its own | Field of the overview |
|---|---|---|
| A finding stays visible if the figures fail | Yes | No |
| Cost of the overview | Unchanged | Unchanged |
| Requests per page | One more | None |

## Consequences

### Positive

- A finding reaches the people who answer for it, in the app and by mail, within a day and without flooding them.
- The regular check of the business concept (7.3) can be seen: whether it ran, when, and what it found.
- Nothing of the chain leaves in a notice.
- The figure of the business concept exists, and a change of function shows up without anyone reporting it.
- A violation found before this feature is told once at the first run after deployment.

### Negative / Cost

- **A job that stops tells nobody.** A stale state shows in the cockpit only, for those who look.
- **Operations depend on the log.** No metric, no health signal until DMS-137.
- **A tenant without officer and administrator hears nothing.** The log stays.
- **A checkpoint mismatch hides what follows.** While one is open a second one is not told; only the newest checkpoint is compared (ADR 0017), so a mismatch can also end by itself when the next checkpoint is written, without anyone having repaired the chain. The run stays on record.
- **Flapping is noise.** A chain that goes intact and violated in turn sends a notice each time.
- **Nothing in the product closes a violation.** There is no way to acknowledge or explain one; the reminders stop after two rounds, the cockpit keeps showing it.
- **Mail can be switched off** per person, also for this category. In-app stays.
- **Someone who left with an open role is not orphaned here.** The figure rests on role assignments; if nobody ends the role, nothing shows. This holds until the directory or an offboarding ends assignments.
- **The figure only marks.** The concept also says a new assignment is started; nothing does that yet.
- The overview reads the Owner assignments of the tenant, one more query per load.
- Officers restricted to a unit see a state of the whole tenant, which is more than they see of documents; it names no document.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: a metric for violations and stale runs once DMS-137 brings the mechanism.
- Should: tell someone when the verification goes stale, not only show it.
- Should: start a new assignment for orphaned documents (business concept 6.2), and let an officer act on the list.
- Could: compare more than the newest checkpoint, so that a mismatch cannot end by itself.
- Could: record who looked at a violation and how it was closed.
- Could: end role assignments when a person leaves the directory.
- Stays open from ADR 0077, a decision of the user with data protection: removal of reports on request of a data subject, and the retention of reports of living documents.
- Ruled out: position, kind or detail in a notice; a violation in `/ready`.

## Reversibility

**high**: Notices are additive: emptying `dms.audit.violationReminderDays` ends the reminders, and removing one call in `verifyFully` ends the notices; the table can stay as a record. The endpoints, the figure and the category are additive. A person's mail setting for a removed category is ignored. Notices that went out cannot be recalled.
