# Module `notifications`

Notifications in the app and by email (business concept 8.3; DMS-130, ADR 0052).

Layout: `api` holds public types and the ports, `core` the implementation. Other modules may only depend on `api`; only `app` wires `core` and implements the ports.

## `api` (package `dms.notifications`)

- `NotificationCategory`: `workflow`, `acknowledgement`, `deputyship`, `publication`, `legal-hold`, each with an email default (all on except legal holds). `Channel`: `in-app`, `email`. `Language`: `de`, `en`.
- `Notice(kind, category, recipients, document, params)`: what an outbox event means for whom. `params` never hold document titles.
- Ports implemented by `app`:
  - `NotificationEvents` (`concerns`, `describe`): outbox event → notices (`PlatformNotificationEvents`).
  - `NotificationDocuments.visibleTo(tenant, user, document)`: title, version label and number if the user may read the document now, else `None` (`PolicyNotificationDocuments`, the policy's `ReadDocument` with current roles, groups, workflow participation and deputies).
  - `RecipientDirectory.people(tenant, users)`: display names and email addresses (`DirectoryRecipients` over the user directory, ADR 0037).
  - `MailSender.send(mail)`: `SmtpMailSender` in `core`.
- Views: `Notification`, `NotificationPage`, `NotificationPreferences`.

## `core` (package `dms.notifications.core`)

- `NotificationJobs(db, events, documents, directory, mail, settings)`:
  - `deliver(event)`: outbox subscriber. In one transaction of the event's tenant one `dms.notification_delivery` row per notice, recipient and channel (in-app always; email if an SMTP server is configured and the recipient wants the category by email) and a job `notifications.deliver` per new row. Unique on (event, kind, recipient, channel): a retried event adds nothing.
  - Job `notifications.deliver`: preference check again (email off → `skipped`), read check (`NotificationDocuments`), rendering in the recipient's language (their choice, else the tenant's), then in-app: insert into `dms.notification` (id = delivery id, exactly once); email: address from the directory (none → `skipped`), send; failure → `retrying` and the job runner's backoff, after `maxAttempts` → `dead`.
  - `redeliver(id)`: a dead delivery back to `pending` with a new job.
  - Job `notifications.purge` (daily per tenant, `tick`/`schedule`): deletes read notifications and delivered/skipped deliveries older than the retention.
- `NotificationTemplates`: German and English subject, text and link per kind; `email` adds greeting, absolute link and footer. Kinds: `workflow.task-assigned`, `workflow.changes-requested`, `workflow.rejected`, `workflow.approved`, `workflow.participant-missing`, `acknowledgement.requested`, `acknowledgement.reminder`, `deputyship.created|started|ended|ended-by-period`, `publication.published`, `legal-hold.created|released`. Without read permission the text names "a document" and no title.
- `NotificationInbox`: the caller's page, unread count, mark read, mark all read, preferences.
- `NotificationStore`: SQL for deliveries, notifications, preferences, the tenant's language and time zone.
- `SmtpMailSender(SmtpConfig)`: Jakarta Mail (Eclipse Angus), plain-text UTF-8, `Message-ID` from the delivery id, `Content-Language`, `Auto-Submitted: auto-generated`; security `starttls` (default), `tls` or `none`.

## Events and recipients (module `app`, `PlatformNotificationEvents`)

| Outbox event | Kind | Recipients | Category |
|---|---|---|---|
| `workflow.task-assigned` (new) | `workflow.task-assigned` | candidates of the task; active deputies of a candidate (text "as deputy of …") | workflow |
| `workflow.returned` | `workflow.changes-requested` / `workflow.rejected` | author and owner | workflow |
| `workflow.approved` | `workflow.approved` | author and owner, not the approver | workflow |
| `workflow.participant-missing` | `workflow.participant-missing` | tenant administrators | workflow |
| `document.acknowledgement-requested` (new) | `acknowledgement.requested` | the asked recipients | acknowledgement |
| `document.acknowledgement-reminder` | `acknowledgement.reminder` | the reminded readers | acknowledgement |
| `workflow.deputyship-created`, `-started`, `-ended`, `-ended-by-period` | `deputyship.*` | principal and deputy (different texts) | deputyship |
| `document.status-changed` Approved → Published | `publication.published` | the owner | publication |
| `legal-hold.created`, `legal-hold.released` | `legal-hold.*` | tenant-wide records managers | legal-hold |

Acknowledgement requests (DMS-100 follow-up): `AcknowledgementReminderJobs` records per version and recipient when they were first asked (`dms.acknowledgement_request`) and publishes `document.acknowledgement-requested`; a publication triggers the check at once. Reminder rounds count per recipient from their request (the first request of a version from publication), so people who become recipients later are not reminded right after joining. Versions valid before V34 are baselined silently.

## Tables (migration V34)

`dms.notification_delivery`, `dms.notification` (only `read_at` may be updated), `dms.notification_settings`, `dms.acknowledgement_request` (insert-only), `dms.acknowledgement_request_baseline`; all tenant-scoped with RLS (`dms.current_tenant()`) and explicit grants to `dms_app`. `dms.tenant.language` (default `de`; `dms_app` may update only this column).

## Configuration (`dms.notifications`)

| Key | Environment | Default | Meaning |
|---|---|---|---|
| `appBaseUrl` | `DMS_NOTIFICATIONS_APP_BASE_URL` | `http://localhost:5173` | Address of the web app for links in emails |
| `maxAttempts` | `DMS_NOTIFICATIONS_MAX_ATTEMPTS` | 8 | Email attempts before a delivery is dead (backoff 10 s doubling, at most 1 h) |
| `retentionDays` | `DMS_NOTIFICATIONS_RETENTION_DAYS` | 180 | Read notifications and finished deliveries are deleted after this |
| `smtp.host` | `DMS_SMTP_HOST` | empty | SMTP server; empty: no email, in-app only (warning at startup) |
| `smtp.port` | `DMS_SMTP_PORT` | 587 | |
| `smtp.security` | `DMS_SMTP_SECURITY` | `starttls` | `starttls`, `tls` (implicit, port 465) or `none` (local test server only) |
| `smtp.username` / `smtp.password` | `DMS_SMTP_USERNAME` / `DMS_SMTP_PASSWORD` | – | Optional authentication; from the secret store |
| `smtp.from` | `DMS_SMTP_FROM` | `DMS <dms@localhost>` | Sender |
| `smtp.timeoutMillis` | `DMS_SMTP_TIMEOUT_MILLIS` | 10000 | Connect, read and write timeout |

Local development: `docker compose up -d mailpit` starts Mailpit (SMTP `localhost:1025`, web UI http://localhost:8025); `scripts/dev-setup.sh` starts the backend with `DMS_SMTP_HOST=localhost DMS_SMTP_PORT=1025 DMS_SMTP_SECURITY=none`.

Production: the company's mail relay with `starttls` on 587 (or `tls` on 465), a sender address of a domain with SPF/DKIM for the relay, credentials only from the secret store, `DMS_NOTIFICATIONS_APP_BASE_URL` set to the public address of the web app. Recipients need an email address in ZITADEL and the user directory token (ADR 0037); without it only in-app notifications are delivered.

## HTTP (module `app`, `NotificationApi`)

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/notifications?unread=&offset=&limit=` | The caller's notifications, newest first, `total`, `unread` | signed in |
| GET | `/api/v1/notifications/unread-count` | Badge of the bell | signed in |
| POST | `/api/v1/notifications/{id}/read` | Mark one read (404 for others' notifications) | signed in |
| POST | `/api/v1/notifications/read-all` | Mark all read | signed in |
| GET / PUT | `/api/v1/notifications/preferences` | Language (`de`, `en`, empty = tenant's) and email per category; `emailAvailable` | signed in |
| GET | `/api/v1/admin/notifications/deliveries?limit=` | Dead deliveries | `Configure` `Notifications` |
| POST | `/api/v1/admin/notifications/deliveries/{id}/redeliver` | Send again (409 unless dead) | `Configure` `Notifications` |
| PUT | `/api/v1/admin/notifications/language` | The tenant's default language | `Configure` `Notifications` |
| GET | `/api/v1/acknowledgements/owned` | Owner tile: own valid versions with open acknowledgements (figures only) | owner (`ViewAcknowledgementPersons`) |

Frontend: the bell in the header (unread badge, polled every minute; the newest notifications; mark all read), `/notifications` (all, unread filter, pages) and `/settings/notifications` (language, email per category); the owner tile on the dashboard.

Tests: `NotificationJobsSpec` (Testcontainers: idempotent fan-out, in-app once, read check at delivery, preferences, retries until dead and redelivery, missing address, unreachable directory, inbox, RLS, clean-up), `SmtpMailSenderSpec` (in-process SMTP test double `FakeSmtpServer`), `NotificationTemplatesSpec` (texts in both languages), `app/NotificationApiSpec` (HTTP), `app/AcknowledgementOverviewApiSpec` (owner tile, requests and late joiners, read check and event mapping with real documents), `app/WorkflowApiSpec` (`workflow.task-assigned`).
