# 0052: Notification delivery: outbox subscriber with one job per recipient and channel, in-app notifications in PostgreSQL, email over SMTP, read check at delivery time

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

Since sprint 6 the platform writes events that are meant for people to the outbox (ADR 0010): reminders of open acknowledgements (DMS-100, ADR 0048), deputyship notices (DMS-99), returned and approved workflow cases, missing participants (ADR 0024). Nobody received them: the notifications module was a placeholder. DMS-130 asks for delivery:

- Channels: in-app notifications (persisted per user, unread count, mark read, list with paging, a bell in the header) and email over SMTP. In-app is always on; email can be switched off per category.
- Sources: workflow tasks assigned, returned, approved, rejected; acknowledgements required and reminders; deputyships; legal holds for records managers; publication of documents one owns.
- Recipients by user id; names and email addresses come from ZITADEL's user service (ADR 0037). A notification must not reveal the title of a confidential document to someone who may not read it (any more) — permissions change between the event and its delivery.
- Idempotent delivery with retries and a dead letter; texts in German and English (the user's choice or the tenant's default); links into the web application.
- Cheap DMS-100 follow-ups: an owner dashboard tile "open acknowledgements of my documents", and people who become recipients later must not be reminded right after they join.

Given: the outbox relay hands every event to subscribers (search index, webhooks) and marks it delivered only when all succeeded; the job queue claims with leases and retries with exponential backoff (ADR 0010); webhooks already use the two-step pattern "subscriber records deliveries + one job per delivery" (ADR 0035). Module rule (ADR 0001): `notifications-core` may depend only on `kernel`, `db`, `storage` and `*-api`; reading documents, authorization and the user directory live in other modules.

## Decision

1. **Two steps, like webhooks.** The outbox subscriber `NotificationJobs.deliver` asks the port `NotificationEvents` (implemented in `app`: `PlatformNotificationEvents`) what an event means: a list of `Notice(kind, category, recipients, document, params)`. In one transaction of the event's tenant it records one row per notice, recipient and channel in `dms.notification_delivery` (unique on event, kind, recipient, channel) and enqueues a job `notifications.deliver` per new row. A retried event adds nothing. Email rows are only recorded when an SMTP server is configured and the recipient's preference for the category is on.
2. **Everything sensitive is decided at delivery time.** Event payloads carry ids, never titles. The job loads the delivery, checks the preference again, asks the port `NotificationDocuments` (in `app`: `PolicyNotificationDocuments`, the authorization policy's `ReadDocument` with the recipient's current role assignments, access group memberships, workflow participation and deputyships) whether the recipient may read the document now and only then gets title, version and number. Without read permission the text names "a document" and no title or number; the link stays (it reveals nothing and the page refuses access).
3. **Rendering.** `NotificationTemplates` holds German and English texts per kind (subject, one or two sentences, path in the web app). Language: the user's choice (`dms.notification_settings.language`), else the tenant's (`dms.tenant.language`, default `de`). Dates use the tenant's time zone. Names of people in the text (who returned a version, the deputy …) come from the user directory; in-app falls back to ids if it is down. Emails are plain-text UTF-8 with greeting, the absolute link (`dms.notifications.appBaseUrl`) and a footer pointing at the settings page; `Message-ID` is derived from the delivery id, so a retried email can be recognized as duplicate; `Auto-Submitted: auto-generated`.
4. **Channels.** In-app: a row in `dms.notification` whose id is the delivery id (exactly once); only `read_at` may change (column grant). Email: `SmtpMailSender` (Jakarta Mail / Eclipse Angus) with `starttls` (default), `tls` or `none` (local only), optional authentication; a new connection per mail. The recipient's address comes from the user directory (`RecipientDirectory`, in `app`: `DirectoryRecipients` over `UserDirectory` and the tenant's organization). No address: `skipped`. Directory or SMTP failure: `retrying`, the job fails and the job runner backs off; after `dms.notifications.maxAttempts` (8) the delivery is `dead`. Tenant administrators list dead deliveries and send them again (`Configure` on the new configuration area `Notifications`).
5. **Preferences.** Per user and category (`workflow`, `acknowledgement`, `deputyship`, `publication`, `legal-hold`) email on/off with a built-in default (all on except legal holds), and the language. In-app notifications cannot be switched off.
6. **Sources.** New outbox event `workflow.task-assigned` (candidates of a new task, and active deputies of a candidate, also when re-resolving adds candidates); `document.acknowledgement-requested` (new, see 7); existing `workflow.returned` (changes requested / rejected), `workflow.approved` (author and owner without the approver), `workflow.participant-missing` (tenant administrators), `document.acknowledgement-reminder`, `workflow.deputyship-created|started|ended|ended-by-period`, `document.status-changed` Approved → Published (owner), `legal-hold.created|released` (tenant-wide records managers). Legal hold changes on single documents are not announced (the records manager made them).
7. **Acknowledgement requests and late joiners (DMS-100 follow-up).** The reminder check records per version and recipient when they were first asked (`dms.acknowledgement_request`, insert-only) and publishes `document.acknowledgement-requested` for the newly asked. A publication triggers a check at once (subscriber on `document.status-changed`). The first request of a version counts from publication; people asked later (they became recipients since) count from their request, and each recipient's reminder rounds count from that time — a late joiner is asked first and reminded a whole interval later. Versions valid before migration V34 are marked as baseline: their current recipients are recorded as asked at publication without a notice, so the rollout sends no burst of requests.
8. **Retention.** A daily job per tenant deletes read in-app notifications and delivered or skipped deliveries older than `dms.notifications.retentionDays` (180). Unread notifications and dead deliveries are kept.
9. **HTTP.** `GET /api/v1/notifications` (paging, unread filter), `GET …/unread-count`, `POST …/{id}/read`, `POST …/read-all`, `GET|PUT …/preferences`, `GET /api/v1/admin/notifications/deliveries` (dead letters), `POST …/deliveries/{id}/redeliver`, `PUT /api/v1/admin/notifications/language`; `GET /api/v1/acknowledgements/owned` for the owner tile. The frontend polls the unread count every minute.

## Alternatives considered

| Criterion (delivery path) | Chosen: subscriber records deliveries, one job per recipient and channel | Send directly in the outbox subscriber | Notification service with its own broker (e.g. a queue per channel) |
|---|---|---|---|
| A slow or failing SMTP server | Only the email job waits and retries | Blocks the relay for all subscribers | Isolated |
| Idempotency | Unique delivery row per event, kind, recipient, channel; in-app id = delivery id | Per event only; partial sends repeat | Broker-specific |
| Visibility, dead letter | SQL rows with status, attempts, last error; admin endpoint | Logs | Broker tooling |
| Fits ADR 0010 / 0035 | Same pattern as webhooks | Contradicts "ruled out: sending in the business path" | New component |

| Criterion (what is rendered when) | Chosen: ids in the event, title and permission at delivery time | Render the text when the event is written | Render when the user opens the bell |
|---|---|---|---|
| Confidential titles | Only if the recipient may read the document at delivery | Title leaks if access was removed meanwhile; event payloads would carry titles | Always current |
| Email | Possible | Possible | Not possible (needs a text to send) |
| Cost | One read check per delivery | None | One per notification per view |
| Text of the past | Stays as delivered (the in-app row is a snapshot) | Same | Changes with the document |

| Criterion (email transport) | Chosen: SMTP with Jakarta Mail | Provider HTTP API (SendGrid, SES, Graph) | Mail via the identity provider (ZITADEL SMTP settings) |
|---|---|---|---|
| Portability / on-premises | Any relay, also the company's | Provider-bound, internet access | Only for ZITADEL's own messages |
| Credentials | Optional user/password from the secret store | API key | – |
| Local development | Mailpit, test double in tests | Provider sandbox | – |

| Criterion (late joiners) | Chosen: request time per recipient | Keep counting from publication (ADR 0048) | Look at the start of the role assignment or membership |
|---|---|---|---|
| Joiner reminded right after joining | No: asked first, reminded an interval later | Yes | No |
| Data needed | One insert-only row per version and recipient | None | Assignment/membership history of several sources (roles, groups, units) |
| Rollout | Baseline for old versions, no burst | – | – |

## Consequences

### Positive

- Every notification-worthy event reaches its recipients in the app; email where configured and wanted.
- A failing SMTP server or directory never holds up the outbox relay; failures are visible and can be sent again.
- No title of a document is sent to someone who may not read it at delivery time; event payloads stay free of titles.
- Texts are maintained in one place for both languages; links lead into the app.
- Owners see their documents with open acknowledgements on the dashboard; late joiners are no longer reminded right away.

### Negative / Cost

- One read check (document, role assignments, groups, workflow cases) per delivery; fine for the expected volume (a reminder round of a large audience means as many checks).
- Email delivery is at least once: if the SMTP server accepted a mail but marking it delivered failed, the retry sends it again (same `Message-ID`).
- The in-app text is a snapshot at delivery: a later title change is not reflected; a later loss of permission does not remove the title from a notification delivered before.
- The bell polls every minute; no push (server-sent events) yet.
- Recipients without an email address in ZITADEL only get in-app notifications; nothing tells the administrator.
- Working days and holidays are not considered for any timing here (deadlines follow in DMS-87).
- The tenant's default language has an API but no admin UI yet.

## Follow-ups

- Must be done: SMTP relay, sender address and `DMS_NOTIFICATIONS_APP_BASE_URL` per environment; credentials in the secret store (`DMS_SMTP_USERNAME`, `DMS_SMTP_PASSWORD`), never in the repository; SPF/DKIM for the sender domain.
- Should: admin UI for dead deliveries and the tenant language; digest emails (daily summary) for high-volume users; push to the bell (SSE) instead of polling.
- Should: escalate open acknowledgements to the unit's management after the last automatic round (ADR 0048 follow-up).
- Later: further sources (comments, review tasks of the periodic review, rendition failures for the owner), per-document muting.
- Ruled out: titles or other document content in outbox payloads; sending from inside business transactions.

## Reversibility

**high**: The module is behind three ports and one outbox subscriber. Replacing SMTP by a provider API changes one adapter; dropping email leaves in-app as it is. The tables can be dropped without affecting business data or the audit trail; acknowledgement requests are additional records whose removal restores the ADR 0048 behaviour.
