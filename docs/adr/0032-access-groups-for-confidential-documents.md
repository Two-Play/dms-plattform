# 0032: Access groups for confidential documents: managed per tenant in the platform, named in a core field

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The business concept (2.3) restricts access from confidentiality "Confidential" on to named groups. `Policy` (ADR 0018) already denies every action on a confidential or strictly confidential version to everyone except its author, its owner and members of one of its `allowedGroups`. Until now the groups were the caller's ZITADEL project roles from the access token, and the version's groups came from an optional tenant field that no class defined. Nothing set the groups, so no reviewer or approver could ever see a confidential version: such documents could not pass review and approval (bug DMS-101), and the tests bypassed the database guards to make a version confidential after publication.

Decisive constraints:

- The workflow resolves participants when it assigns a task (business concept 6.1: "from roles, not from named persons"). For a confidential version it must know which role holders may see it, i.e. the group memberships of **other** users at assignment time. An access token only tells the groups of the caller.
- The search (ADR 0029) filters by the caller's groups at query time; changes must take effect within 60 seconds (US-09.2).
- ZITADEL (ADR 0005) is the identity provider. It has no group concept of its own; project roles and user grants are its means to model authorization, and reading other users' grants needs a service user and the management API.
- Every change of permissions must be traceable (business concept 7.3; recertification US-11.2 later).

## Decision

Access groups are kept per tenant in the platform: `dms.access_group` (key, name) and `dms.access_group_member` with a history of memberships (migration V17, RLS and explicit grants to `dms_app`). Tenant administrators manage them through `/api/v1/groups` (configuration area `Groups`); changes are recorded in the audit trail. `Authorizer.subject` fills `Subject.groups` from the caller's current memberships; ZITADEL project roles are no longer used as groups. A document version names its groups in the new core field `allowedGroups` (list of group keys): entered with the other field values on creation and in the draft, checked for format and existence (422 `pattern_mismatch` / `unknown_option`), frozen with the version (database guards of ADR 0021) and changed only through a new version. From review on, a confidential or strictly confidential version requires at least one group (422 `missing_required` on submission); a draft without groups is visible to author and owner only.

The workflow considers only role holders who may see the version: `ParticipantDirectory.cleared` keeps author, owner and current members of the version's groups (`ReviewSubject.accessGroups`). Without an eligible member the task waits and the tenant administrators are notified (DMS-42, payload `confidential: true`); after adding a member they resolve the participants again. Deputies who may not see the version are not offered. The task inbox leaves out tasks of versions the caller may no longer see.

## Alternatives considered

| Criterion | Chosen: groups managed in the platform | A: ZITADEL project roles as groups (token claim) | B: ZITADEL user grants read through the management API |
|---|---|---|---|
| Workflow can tell which role holders may see a version | Yes, one query in the same transaction | No: only the caller's token carries their roles | Yes, through an HTTP call per resolution |
| Read check, search filter and workflow on the same data | Yes | Workflow cannot use it | Yes, if the grants are cached consistently |
| Effect of a membership change | Next request (search filter at query time) | Next token (minutes, refresh token lifetime) | Next lookup (cache lifetime) |
| Audit trail and history of memberships | In the platform's audit trail, rows kept | In ZITADEL's events only | In ZITADEL's events only |
| Tenant isolation | RLS like all tenant data (ADR 0014) | Per ZITADEL organization | Per ZITADEL organization |
| Operations and dependencies | None new; administration API in the platform | None | Service user, credentials, network dependency at task assignment |
| Group management where users already are | No: a second place besides ZITADEL | Yes | Yes |
| Effort | Medium (tables, API, UI later) | Low, but does not fix the workflow | High |

Also considered for the business rule: author-and-owner-only access for confidential versions without groups. Rejected for submitted versions because the concept restricts access to *named groups* and a review needs readers besides the author; kept for drafts.

## Consequences

### Positive

- Confidential documents go through review and approval; tests run the full flow without bypassing database guards.
- One source of group memberships for `Policy`, the search filter, the workflow and the task inbox.
- Membership changes take effect on the next request and are traceable (audit trail, rows with `removed_at`).
- The core field makes the groups part of the validated, frozen version and of the search index without class configuration.

### Negative / Cost

- Groups are administered in the platform, not in ZITADEL; an administration UI is still missing (API only).
- Open tasks are not re-resolved when memberships change: a removed member keeps the candidate entry but can no longer read or vote, and the task disappears from their inbox; an administrator resolves the participants again if nobody is left.
- Tenants can no longer define a field with key `allowedGroups` (it is a core field now); a tenant field of that name defined after DMS-51 would have to be removed from its classes.
- Token roles no longer grant access to confidential documents; whoever relied on ZITADEL project roles as groups needs memberships in the platform.

## Follow-ups

- Must be done: administration UI for groups and members (with role assignments, ADR 0018 follow-up).
- Must be done: recertification of group memberships by owners (US-11.2).
- Later: optional synchronization from an external directory (ZITADEL user grants or SCIM) into the tables, keeping the tables as the source for decisions.
- Ruled out: deleting groups (versions keep naming them); renaming keys.

## Reversibility

**medium**: Callers only see `Groups` and `Subject.groups`. A synchronization from ZITADEL could fill the same tables; replacing the tables with live lookups would touch `Groups.Postgres`, the workflow's `RoleDirectory` and the group API. The core field and the stored group keys stay valid either way.

## Addendum (DMS-105, 2026-10-29): administration UI and automatic re-resolution

- **Administration.** Tenant administrators manage groups in the web UI (Admin → Access groups): create, rename (`PUT /api/v1/groups/{key}` with `{name}`; the key stays, audit `group.renamed` with the previous name), add and remove members with the membership history. The follow-up "administration UI for groups and members" is done; role assignments are still API only.
- **Re-resolution.** Adding or removing a member now resolves the participants of the open and waiting tasks of confidential versions naming the group again, in the same transaction as the membership change (`ApprovalWorkflow.resolveParticipantsOfGroup`, the logic of `POST /api/v1/workflow/tasks/{id}/participants`). Tasks whose candidates and status stay the same are left untouched and not audited; changed ones are audited as `workflow.participants-resolved` with the administrator as actor, and a task left without participants waits and notifies the tenant administrators (`workflow.participant-missing`). The negative consequence "open tasks are not re-resolved when memberships change" no longer applies.
- **Alternatives.** A job triggered by an outbox event of the membership change would keep the request short and survive large numbers of open tasks, but the inbox would be stale until the job ran and the administrator would not see a failure. Chosen: same transaction, since a tenant has few open tasks and a failure rolls back the membership change together with the tasks.
- **Cost.** The change reads all open and waiting tasks of the tenant and loads the document of each of their cases to see whether the version names the group (the workflow does not store the groups). If that becomes slow, the case could store the version's groups (migration) or the re-resolution could move to a job.
- **Deputies.** Deputies of candidates take part in a confidential version only while they are members of one of its groups themselves (`RoleDirectory.cleared`, unchanged); `app/WorkflowApiSpec` covers this together with the re-resolution.
