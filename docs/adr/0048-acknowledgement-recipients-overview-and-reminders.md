# 0048: Acknowledgements: recipients per document class as a filter on the readers, an owner overview with purpose-bound names, idempotent reminders through the outbox

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-100 (US-06.2 follow-up of DMS-47) asks for three things that ADR 0027 left open:

1. **Overview for owners**: per valid version that requires acknowledgement, who must acknowledge, how many have, the list of people (display names, ADR 0037), open/done filter, export.
2. **Reminders**: automatic reminders of open acknowledgements after a configurable period (per class or tenant default), and a manual "send reminder" for owners, rate-limited and audited, through the existing outbox; no duplicate reminders.
3. **Recipients per document class** ("Empfängerkreis"): configure in the class settings who must acknowledge and apply it when a version is published; decide what happens to people who join later.

Given:

- ADR 0027: the audience is everyone who may read the valid version (`ReadPublished`), evaluated on request, not stored; open acknowledgements are derived. ADR 0036: `dms.authz.Audience` decides the readers of many versions at once; names of readers are purpose-limited (`ViewAcknowledgementPersons` for compliance officers in scope and the document's owner, stated purpose and reason, audited per access). The cockpit's person list returns only open user ids.
- The authorization model knows business roles with optional organizational unit and class scope (`dms.role_assignment`) and access groups (ADR 0032). There is no membership of organizational units apart from scoped role assignments.
- Document classes are versioned (`document_class_version`, ADR 0019); review settings are columns of the class version. Classes form a tree (`parent_id`).
- The notifications module is a placeholder; deputyship notices (DMS-99) and review tasks publish outbox events that it will deliver. The outbox relay hands events only to subscribers; webhooks subscribe to an explicit list of event types (ADR 0035).
- Migration V29 is reserved for this change; DMS-106 (legal hold UI) runs in parallel with V30 and ADR 0047.

## Decision

1. **Recipients are a filter on the readers, not a list of people.** Settings (`dms.acknowledgement_settings`, one row per class, at most one tenant default) name business roles, access groups and organizational units. A reader is a recipient if there is no restriction, or if they hold an active assignment of one of the roles (any scope), are a current member of one of the groups, or hold an active assignment restricted to one of the units (any of the three, `dms.authz.RecipientFilter`). Only readers can be recipients: the filter narrows `ReadPublished`, it never widens it.
2. **Inheritance and snapshot at publication.** A class without settings inherits those of its nearest ancestor with settings, then the tenant default, then "every reader". When a version that requires acknowledgement becomes valid (Approved → Published), the resolved settings are recorded in `dms.version_acknowledgement_audience` in the same transaction (insert-only). Changing the settings later affects versions published afterwards only. A reminder interval left open on a class is taken from the tenant default, then from the installation (`dms.documents.acknowledgementReminderDays`, default 14, 0 = off).
3. **People joining later are asked as well.** Only the criteria are recorded, membership is evaluated when asked (as in ADR 0027): who gets the role, joins the group or gets a role in the unit later becomes a recipient of the valid version; who loses it drops out (their acknowledgement stays recorded but no longer counts in the figures, as for readers in ADR 0036). Readers outside the recipients are not asked: their open list, the library's "pending" flag and the document page leave the version out, and acknowledging answers 422 `acknowledgement_not_requested`.
4. **Owner overview.** `GET /api/v1/documents/{id}/acknowledgement-overview` returns per valid version that requires acknowledgement the recorded recipients and their source, the reminder interval, audience/acknowledged/open/rate and the manual reminder state; figures only, for `ViewAcknowledgementPersons` (owner, compliance officers in scope; 403 otherwise). Names come from `GET …/versions/{versionId}/acknowledgement-recipients?purpose=&reason=&status=`, with the same purpose limitation and audit entry (`compliance.acknowledgement-persons-viewed`) as the cockpit. Unlike the cockpit's list it names done recipients with their acknowledgement time, because the owner needs to see the proof of their own policy; opening times are not shown (behaviour data). Display names are resolved from the user directory after the transaction (ADR 0037); without directory the ids are shown. CSV is produced in the browser from the list already loaded, so an export is no separate, unaudited access.
5. **Reminders.** Every reminder is one row per reader in `dms.acknowledgement_reminder` (insert-only) and one outbox event `document.acknowledgement-reminder` per version and send, naming the reminded user ids, for the notifications module; the audit trail (`document.acknowledgement-reminded`) records the number of readers, not their names.
   - *Automatic*: a job per tenant and check interval (job queue with idempotency key, like the deputyship check) sends round *n* once *n* whole reminder intervals have passed since the settings were recorded, at most three rounds, to the recipients who have not acknowledged. A unique index on (version, reader, round) makes retries and concurrent jobs send each reminder once; missed rounds are not caught up. Versions valid before V29 get a snapshot with "every reader" at migration time, so their intervals start then and the first run does not remind of every old version at once.
   - *Manual*: `POST …/versions/{versionId}/acknowledgement-reminders` by owner or compliance officer reminds all open recipients, at most once per 24 hours and version (429 `reminder_too_soon`, checked under a transaction-scoped advisory lock), 422 `nothing_open` if nobody is open. The caller learns how many were reminded, not who.
6. **Administration.** `GET/PUT/DELETE /api/v1/admin/acknowledgement-settings[/tenant|/classes/{classId}]` for `Configure` on document classes (tenant administrator); unknown roles or groups are rejected (422). The frontend has an admin tab "Kenntnisnahme" and an overview card on the document page.

## Alternatives considered

| Criterion (what "recipients" is) | Chosen: filter on readers (roles, groups, units) | Explicit list of people per class | Materialized recipients per version at publication |
|---|---|---|---|
| Consistent with read access | Always a subset of the readers | Lists drift from access; people who cannot read would be asked | Subset at publication, drifts afterwards |
| People joining later | Asked automatically | Must be added by hand | Not asked unless re-materialized (fan-out job on every role change) |
| Fits the existing model | Uses roles, groups and scoped assignments as they are | New person lists to maintain | New table per version × person |
| Reporting cost | Same as the cockpit: profiles × versions | Cheap | Cheap |
| Effort | One filter class, two small tables | Admin UI for people, sync with the directory | Fan-out job, invalidation |

| Criterion (where settings live) | Chosen: own table per class, snapshot per version | Columns of the class version (like the review settings) | Read live from the class at evaluation time |
|---|---|---|---|
| "Applied when a version is published" | Yes, snapshot | Yes, via the class version of the document | No: a change rewrites history of valid versions |
| Changing settings | One request, no new class version, no field list to resend | Needs a full new class version | One request |
| Inheritance to subclasses | Resolved at publication along the class tree | Would need to be copied into every subclass version | Resolved per request |
| Effort | Two tables, one admin API | Migration of the class version, API change of class versions | Smallest |

| Criterion (reminder idempotency) | Chosen: row per version, reader and round with unique index | Notice row per version and day | Reminder flag on an open-item row |
|---|---|---|---|
| No duplicates on retry/concurrency | Yes (insert … on conflict do nothing, publish only what was inserted) | Yes per day, but daily repeats without bound | Needs materialized open items (rejected above) |
| Bounded number of reminders | Yes, three rounds | No | Yes |
| Late joiners | Get the current round once | Daily | – |

Names in the overview without stated purpose were rejected for the same reasons as in ADR 0036 (business concept 10.2).

## Consequences

### Positive

- Owners see the state of their policies at a glance and can remind with one click; compliance officers use the same card.
- One audience rule everywhere: the reader's open list, the document page, the library, the cockpit, the owner overview and the reminders all use `ReadPublished` plus the recorded filter.
- Reminders are idempotent and bounded; every manual reminder and every name access is audited.
- Classes can target, e.g., only HR or one access group without narrowing read access.

### Negative / Cost

- Organizational units are matched only through role assignments restricted to them; a tenant-wide reader is not "in" any unit. Tenants that want units as recipients must scope assignments accordingly.
- The overview and reminder job evaluate the policy for the whole tenant population per request/run (as the cockpit); very large tenants may need a summary table later.
- A late joiner can receive the current reminder round right after joining. Reminders are only handed to the outbox: until the notifications module delivers them (mail, in-app), nobody receives them; the task inbox still shows open acknowledgements.
- Settings are snapshotted: correcting a wrong recipient filter for an already valid version needs a new version.
- The reminder intervals count from the snapshot time (publication); the valid-from date of a scheduled publication is the same moment.
- Recipient settings and acknowledgement figures leave the evidence report (DMS-57) unchanged; it still lists acknowledgements, not recipients.

## Follow-ups

- Must be done: deliver `document.acknowledgement-reminder` in the notifications module (mail, in-app), with the reader's language.
- Should: show recipients and reminders in the evidence report; escalate to the unit's management after the last automatic round.
- Should: a dashboard entry for owners listing all their documents with open acknowledgements.
- Later: organizational units as a membership of their own (directory or HR system) instead of scoped role assignments.
- Ruled out: names of recipients without stated purpose; per-person recipient lists.

## Reversibility

**medium**: The settings and snapshot tables can be dropped (every reader is asked again) without touching the acknowledgement proof. Reminder rows are append-only records and stay. Switching to materialized recipients would replace `RecipientFilter` evaluation by a join, behind the same endpoints.
