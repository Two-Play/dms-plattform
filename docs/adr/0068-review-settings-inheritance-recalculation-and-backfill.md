# 0068: Review settings inherited as a whole from the latest ancestors, review dates recalculated by a job with fixed limits, backfill in the review check

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

ADR 0030 left three points open, now the remainder of DMS-104: review settings are not inherited from parent classes, a new interval of a class does not reach existing review dates, and versions published before V18 have a review date only if their `nextReview` value was set. Notification and escalation are done (DMS-141, ADR 0057).

Asked for: inheritance; a recalculation of open review dates with a record of it, each changed date audited, tasks already decided not touched, no silent move that hides an overdue review; a backfill by migration or idempotent job that respects RLS and the guards.

Given:

- Business concept 3.1: classes are hierarchical and "erben Regeln vom Elternknoten". 6.2: cycle per class, shorter per document; the next date is computed on every approval; overdue reviews stay valid and are marked and escalated.
- `dms.document_class_version` is append-only (guard, no update grant) and a child pins a parent version for its fields. ADR 0030: the settings of a class's latest version apply to all its documents, without a profile change. Retention rules (DMS-133) follow `parent_id` to the nearest ancestor.
- `review_interval_months` empty means "no periodic review"; `review_lead_days` is `not null default 60`. So a stored version cannot say whether a subclass chose these values or never set anything.
- The date of a `dms.document_review` row is part of its identity (guard), at most one task per version is open, and `document_review_first_of_date` allows one first row per version and date.
- Deadline notices (ADR 0057) hang on the task and its `due_on`. `ReviewStore.tasksToOpen` looks for a row of the current date.
- The audit trail is a hash chain written by the application (ADR 0017); a migration cannot add to it. Migrations run as owner, outside the tenant context.
- A recertification does not record whether the owner chose the date or took the default; the date lives in `next_due_on` and on the version.
- Class settings have no screen in the frontend yet; they exist in the API only.

## Decision

### Inheritance

1. **A class version has its own settings or inherits both.** New column `document_class_version.review_inherited`; `ReviewPolicy.inherited`. Inherited means interval and lead time, as a whole. A subclass that wants another lead time also states the interval. Own settings without an interval stay possible: an explicit "no periodic review" for a subclass of a class with a cycle.
2. **Ancestors count with their latest version**, by `parent_id`, not by the pinned `parent_version`: the same rule as ADR 0030 decision 1 and as retention rules, so a parent's change reaches its subclasses at once. One definition in SQL, `dms.class_review_policy(class)`, which also serves the Scala side (`DocumentClassService.reviewPolicy`) and the review queries. It is `security invoker`, so RLS applies. A root class always has its own settings; a database check rejects "inherited" without a parent.
3. **Defaults.** A new subclass inherits unless the request says otherwise; a new root class has no interval and 60 days. A new class version keeps what the previous one had, as before. The API takes `review.inherited`; it shows the version's own values, `inherited`, and the settings in force now (`effectiveIntervalMonths`, `effectiveLeadDays`).
4. **Existing subclasses.** The column stays empty on existing rows, which keeps the table append-only. `dms.class_version_inherits_review` reads an empty value as "inherits" for a subclass version with no interval and the default lead time, because that is what never set anything looks like, and business concept 3.1 says what then applies. Every other existing version keeps its own settings.

### Recalculation

5. **Trigger.** When a new class version changes the interval in force of that class, `DocumentClassService.newClassVersion` records `document-class.review-interval-changed` (previous and new interval) and enqueues `document.review-recalculation` in the same transaction. Lead time changes need none: they apply through the check anyway. A subclass that switches between its own and the same inherited value triggers nothing.
6. **Scope.** The valid versions of the class and of the subclasses that inherit through an unbroken line, each in its own transaction (`ReviewJobs.recalculate`). The job reads the settings in force when it runs, so several changes in a row end in the latest one.
7. **The new date** (`Review.recalculated`, pure). The cycle of publication: valid-from date plus interval, shortened by `nextReview`. If the version was recertified, the cycle counts from the day of the latest recertification, without `nextReview`.
8. **The limits.** Checked in this order, under the row locks:

   | Case | Result | Reason |
   |---|---|---|
   | No interval in force | Date stays (`NoInterval`) | Nothing says what the date should be; a review is never dropped silently |
   | Date reached (`<= today`, also before the check marked it) | Date stays, both directions (`Overdue`) | Only a decision ends an overdue review. A later date would hide it, and an earlier one rewrites how late it is |
   | A task for this date is decided, e.g. a revision is under way | Date stays (`Decided`) | Moving it detaches the decision from the date, and `lostToRevision` could no longer reopen the task |
   | Recertified and the new date is later | Date stays (`Recertified`) | The owner decided; a longer cycle takes effect at the next decision |
   | Open task and the new date is later | Date stays (`OpenTask`) | The owner has been asked; the task must not vanish from the inbox. The decision uses the new interval |
   | Otherwise | Date moves | |

9. **An earlier date ends the open task.** The guard keeps a task's date, so the open task is closed as `Obsolete` (`document.review-task-closed`, reason `review-date-moved`), and a new task for the new date opens in the same transaction, with its own created notice and deadline stages. Decided tasks are never written.
10. **Never before today.** A computed date that has passed becomes today, and the version is marked ReviewDue at once. Nobody could have acted on the past date, and backdating it would escalate immediately and overstate how late the owner is. The audit entry keeps the computed date.
11. **Record.** Each changed date: audit and outbox `document.review-date-changed` (previous, next and computed date, trigger, class version, interval) as `system:review`. Each date kept against the interval: `document.review-date-kept` with the reason, so a person can see why an overdue review did not move. Both also get a row in `dms.document_review_recalculation` (migration V46; RLS, `dms_app` select and insert only, append-only guard), primary key class version + document version, written in the same transaction. A job that runs again finds the row and records nothing twice, the pattern of `dms.document_review_deadline_notice`.

### Backfill

12. **A step of the review check, no migration.** `ReviewService.pendingWork` lists valid versions without a date whose class has an interval in force; `scheduleMissing` sets the date in one transaction per version, with the same rules (decision 7, decision 10) and the same audit entry (trigger `missing-date`). It runs as `dms_app` in the tenant's transaction, so RLS and `document_version_review_guard` apply, and it is idempotent because a dated version drops out of the list. The step stays: a class that gets an interval later, or that inherits one, is covered the same way. No data is changed by V46.

## Alternatives considered

| Criterion (what is inherited) | Chosen: both settings as a whole | Each setting on its own | Copy the parent's values when the class is created |
|---|---|---|---|
| A parent's later change reaches subclasses | Yes | Yes | No: this is what the business concept rules out |
| Stored states | Own or inherited | Two flags, mixes such as "own lead time, inherited none" | None added |
| Subclass with another lead time only | Must state the interval and stops following it | Works | Works |
| Fits retention rules (own or nearest ancestor) | Yes | Partly | No |

| Criterion (which parent version counts) | Chosen: latest version, by `parent_id` | Pinned `parent_version` |
|---|---|---|
| Fits ADR 0030 "latest settings apply" | Yes | No: subclasses would need a new version to follow |
| Parent tightens the cycle | Reaches subclasses at once | Silently stays behind until someone pins again |
| Fields | Unchanged, still pinned | Unchanged |

| Criterion (existing subclasses with nothing set) | Chosen: read as inherited | Keep "no interval" until an admin saves a new version | Rewrite the rows in V46 with the guard switched off |
|---|---|---|---|
| Business concept 3.1 | Met at once | Met only per class, after someone acts | Met at once |
| Append-only table | Kept, with one rule on read | Kept | Guard bypassed, a first in this repository |
| A deliberate "none" on a subclass | Now inherits; needs a new version with own settings | Kept | Same as chosen |
| Failure when wrong | Visible: tasks appear | Invisible: documents stay out of the cycle | Visible |

| Criterion (running the recalculation) | Chosen: job, a transaction per version | In the transaction of the class change | Read the interval at query time, no stored date |
|---|---|---|---|
| Size of the transaction | Small | All valid versions of a subtree | None |
| A failing version | Retried alone | Blocks the class change | – |
| Audit per changed date | Yes | Yes | No date changes to audit |
| Frozen decisions, owner's dates | Respected | Respected | Needs a rewrite of ADR 0030 decision 2 |
| Delay | Until the job ran | None | None |

| Criterion (an overdue date when the interval gets longer) | Chosen: date stays until a decision | Moves with the interval | Moves, with a marker "was overdue" |
|---|---|---|---|
| Overdue review stays visible | Yes | No: the version would be ReviewDue with a date in the future, or return to Published without a decision | Only for who reads the marker |
| Cockpit and escalation | Unchanged | Stop | Stop |
| Who ends it | The owner, recorded | A class setting | A class setting |

| Criterion (a recertified date) | Chosen: only earlier, counted from the recertification | Never moves | Moves in both directions |
|---|---|---|---|
| Owner's decision | Kept, unless the class demands an earlier review | Kept | Overruled, also for a chosen earlier date |
| Class tightens its cycle | Reaches recertified versions | Reaches them only at the next decision, up to the old interval later | Reaches them |
| Row of the decided task | Unchanged | Unchanged | Unchanged |

| Criterion (a date that has already passed) | Chosen: today | Keep the computed past date | Today plus the lead time |
|---|---|---|---|
| Review marked at once | Yes | Yes | No: up to a year of grace hides that it is due |
| Immediate escalation | No, the stages count from today | Yes, for every old document at once | No |
| Days overdue in the cockpit | From now | From a date nobody knew | From later |

| Criterion (backfill) | Chosen: step of the review check | Flyway migration | Separate one-off job |
|---|---|---|---|
| Audit per date | Yes | No: the hash chain is written by the application | Yes |
| RLS and guards | Apply, runs as `dms_app` | Bypassed: owner, no tenant | Apply |
| Tenant time zone | Yes | Needs a join in SQL | Yes |
| Classes that get an interval later | Covered | Not covered | Needs a trigger of its own |
| Cost | One indexed query per check | None after the run | A job kind and something that enqueues it once per tenant |

## Consequences

### Positive

- The business concept's rule holds for reviews: set a cycle on a class, and the subclasses follow, also later.
- A changed interval reaches open dates without anyone touching documents, and every changed or kept date can be traced to the class version behind it.
- Nothing hides an overdue review: its date, status, task and notices stay until the owner decides.
- Decided tasks are not written, the frozen package stays as it is, and the existing guards are unchanged.
- No version of a class with an interval stays without a date, whatever the reason for it was.

### Negative / Cost

- **Rollout burst.** On the first check after deployment, old versions in classes with an interval in force get a date, most of them today: ReviewDue, a task and a created notice each. Subclasses that inherit from now on add to it. The stages of ADR 0057 follow days later. Like the burst of ADR 0057 it is intended but should be announced.
- A deliberate "no interval" on an existing subclass with the default lead time cannot be told from one never set, so it inherits after V46.
- A subclass that only wants another lead time gives up following the parent's interval.
- A longer interval does not reach versions with an open task or a recertified date until the next decision, so their cycle can stay shorter than the class says for that long.
- The date counted from a recertification ignores a date the owner chose: it can only pull a chosen date earlier, and a tightened class may override an owner's later choice.
- The "date moved" records of a version are in the audit trail and the new table, but the owner is only told through the new task. A date moved with no task yet reaches nobody.
- `Obsolete` now also means "the date moved", not only "the version stopped being valid". The audit entry tells them apart.
- `dms.class_version_inherits_review` keeps a rule for old rows for as long as they exist.
- A date moved back to a day whose task was closed as `Obsolete` gets no new task, because of the unique index on version and date. It needs a shortening and a later return to exactly that date with no decision in between; the version still becomes ReviewDue on the date.
- A publication running while a class changes can compute its date with the old interval if it read the settings before the commit; the job does not see that version unless it is already valid. The date stays until the next class change or decision.

## Follow-ups

- Must be done: announce the rollout burst to the tenants, together with the one of ADR 0057.
- Should: an admin screen for review settings (own, inherited, in force); today only the API has them.
- Should: tell the owner when a date moves (`document.review-date-changed` is on the outbox, no subscriber yet).
- Should: a report of dates kept against the interval, from `dms.document_review_recalculation`.
- Later: inherit the lead time on its own if tenants ask for it; mandatory cycles a subclass cannot opt out of (business concept 9: minimum rules).
- Ruled out: moving an overdue date through a class setting.

## Reversibility

**medium**: The recalculation is one job kind and a pure rule; dropping it leaves dates as they are, and the recalculation table can be dropped. Changing the limits needs no migration. Inheritance is stored in class versions from now on, so going back means reading `review_inherited` as false, which takes the interval away from inheriting classes for later publications. Dates once moved or backfilled stay; the audit trail says what they were.

## Addendum 2026-11-06

The user confirmed on 2026-11-06 that a date chosen at recertification may move earlier when a class's interval gets shorter: the stricter class cycle wins.
