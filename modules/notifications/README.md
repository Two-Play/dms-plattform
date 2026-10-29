# Module `notifications`

Notifications, reminders, digests (business concept 8.3).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Events to deliver

- `document.acknowledgement-reminder` (DMS-100, ADR 0048): reminder of open acknowledgements, automatic (`kind` automatic, `round` 1 to 3) or sent by an owner (`kind` manual). Payload: `documentId`, `versionId`, `version`, `title`, `kind`, `round`, `userIds` (the readers to remind). Not offered to webhooks.
- `workflow.deputyship-started`, `workflow.deputyship-ended-by-period` (DMS-99, ADR 0028 addendum).
