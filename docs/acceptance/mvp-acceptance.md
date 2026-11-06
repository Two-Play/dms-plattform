# MVP acceptance report

As of 2026-11-04 · Jira DMS-60 (T-00.13) · Code state: `main` at 46347af plus branch `DMS-60-mvp-acceptance`.

**This report prepares the acceptance; it is not the acceptance.** The sign-off is the product owner's. Nobody has walked through the stories as a business user for this report.

## Result

- **Scope:** 44 planned MVP items (`docs/backlog.md`, DMS-17 to DMS-60). 40 are done, 3 partly done, and T-00.13 waits for the sign-off.
- **Jira:** every planned MVP story and task except DMS-60 is Done. 14 follow-up issues are still open. None of them can be closed as written; see [Open follow-ups](#open-follow-ups).
- **Load test:** carried out at 1:100 of the volumes of section 14.2, with the result in [Load test](#load-test). It found a total outage under load, which is fixed.
- **High-priority defects:** Jira has no open bug. The defects the load test found are fixed on this branch.
- **Release:** the four features that blocked the first production stage are built (DMS-141 to DMS-144), and a set of operational tasks must be done before going live; see [What blocks an MVP release](#what-blocks-an-mvp-release).

## How the status was decided

Each story was compared with its acceptance criteria (`docs/backlog.md`, and chapter 12 of the [business concept](../fachkonzept.html) for stories marked FK) by reading the code and the automated tests.

| Status | Meaning |
|---|---|
| done | Every acceptance criterion is met by code with an automated test or a usable UI path |
| partly | At least one criterion is not met; the gap is named |
| open | Nothing usable exists |

The automated tests ran for this report (see [Checks](#checks)). Browser walkthroughs were not repeated for it. Follow-ups that go beyond a story's acceptance criteria do not lower its status; they are listed separately.

## Stories and tasks

PR numbers refer to GitHub pull requests; early items without a number were committed before pull requests were in use.

### Foundation

| ID | Jira | Title | Status | Evidence | Remaining gap |
|---|---|---|---|---|---|
| T-00.1 | DMS-17 | Project scaffold | done | Module rule in `build.sbt`; `GET /health`, `/docs` | |
| T-00.2 | DMS-18 | ADRs | done | `docs/adr/`, index in its README | |
| T-00.3 | DMS-19 | Local environment | done | `compose.yaml`, `scripts/dev-setup.sh` | |
| T-00.4 | DMS-20 | CI pipeline | done | #1, `.github/workflows/ci.yml` | |
| T-00.5 | DMS-21 | Spike database access | done | #3, ADR 0012 | |
| T-00.6 | DMS-22 | Spike Flowable | done | #2, ADR 0006 | |
| T-00.7 | DMS-23 | Spike PDF/A rendering | done | #9, ADR 0008 | |
| T-00.8 | DMS-24 | ZITADEL license | done | #5, ADR 0005 | |
| T-00.9 | DMS-25 | Database foundation, RLS | done | #4, ADR 0014, `DbSpec` | |
| T-00.10 | DMS-27 | Frontend scaffold | done | #7, ADR 0015 | |
| T-00.11 | DMS-30 | Object storage with Object Lock | done | #12, ADR 0016, `ObjectStoreSpec` | |
| T-00.12 | DMS-31 | Outbox and job queue | done | #15, ADR 0010, `WorkersSpec` | |
| T-00.13 | DMS-60 | Hardening, load test, acceptance | done with this branch | ADR 0056, `scripts/loadtest/`, this report | Sign-off |

### Identity, permissions, audit

| ID | Jira | Title | Status | Evidence | Remaining gap |
|---|---|---|---|---|---|
| S-11.a | DMS-26 | Login with tenant resolution | done | #8, `AuthenticatorSpec` | |
| S-11.b | DMS-28 | RBAC and ABAC | done | #10, ADR 0018, `PolicySpec`, `AuthorizerSpec` | |
| S-12.a | DMS-29 | Audit trail with hash chain | done | #11, #13, #14, #16, ADR 0017, `AuditCheckpointsSpec` | Production needs a bound TSA; the profile refuses to start without it |

### Templates, metadata, documents

| ID | Jira | Title | Status | Evidence | Remaining gap |
|---|---|---|---|---|---|
| S-02.a | DMS-32 | Classes and field definitions | done | #17, ADR 0019 | |
| US-02.1 | DMS-33 | Status-dependent mandatory fields | done | #17, `RequirednessSpec` | |
| S-01.a | DMS-34 | Templates with placeholders and CEL | done | #18, ADR 0020 | |
| S-01.b | DMS-35 | Template lifecycle, four eyes | done | #18, `TemplateApiSpec` | |
| US-01.1 | DMS-36, DMS-154 | Locked and editable sections | done | #20, #25, #26, #98, ADR 0022, 0069, 0071, `DocumentApiSpec`, `WorkflowApiSpec`, `deviations-panel.test.tsx`, `section-text-editor.test.tsx` | Not yet checked in a real browser: DMS-157 |
| US-01.2 | DMS-50 | Impact analysis | done | #39, `TemplateImpactApiSpec` | |
| S-03.a | DMS-37 | Document from template | done | #23, `DocumentApiSpec` | |
| S-05.a | DMS-38 | Frozen version packages | done | #23, ADR 0021, `DocumentVersionSchemaSpec` | |
| US-03.2 | DMS-39 | Live preview | done | #22, ADR 0039; p95 from the load test | |
| US-03.1 | DMS-43 | Partner master | **partly** | #29, ADR 0023, `PartnerApiSpec` | The criteria are met against the demo directory. The HTTP contract is an assumption and no real partner system is connected (DMS-89). Stage 2 |

### Workflow and publication

| ID | Jira | Title | Status | Evidence | Remaining gap |
|---|---|---|---|---|---|
| S-04.a | DMS-40 | Approval workflow | done | #28, ADR 0024, `WorkflowApiSpec` | Review modes and metadata conditions: DMS-88 |
| US-04.1 | DMS-41 | Approval bound to a version | done | #28, `WorkflowApiSpec` | |
| US-04.2 | DMS-42 | Four-eyes principle | **partly** | #28, `WorkflowApiSpec` | Enforced, also with a notice to the administrator when nobody is eligible. Configurable per class for review steps only: the final approval always keeps four eyes, which is stricter than the criterion (DMS-88) |
| US-06.1 | DMS-44 | PDF/A rendition | done | #33, #81, DMS-142, ADR 0025, 0055, 0058, `PdfRenditionSpec`, `RenditionApiSpec` | The status stays "In approval" on failure; a dead job notifies owner and records managers in the app and by email, with a link to the retry |
| US-06.3 | DMS-45 | Scheduled publication | done | #32, ADR 0026, `VersionPublicationSpec` | Failure notice with retry: DMS-142, ADR 0058. Withdrawal: DMS-143, ADR 0059 |
| US-05.2 | DMS-46 | Editorial correction | done | #32, `PublicationApiSpec` | |
| US-06.2 | DMS-47 | Acknowledgement | done | #41, ADR 0027, `AcknowledgementApiSpec` | |
| S-10.a | DMS-48 | Central task inbox | done | #38, `task-inbox-page.test.tsx` | |
| US-10.1 | DMS-49 | Delegation for approvers | done | #40, ADR 0028, `DeputyshipsSpec` | |

### Search, review, archive, reports

| ID | Jira | Title | Status | Evidence | Remaining gap |
|---|---|---|---|---|---|
| S-09.a | DMS-51 | Full-text indexing | done | #44, ADR 0029, `SearchIndexSpec` | Production operation: DMS-102 |
| US-09.2 | DMS-52 | Permission-filtered search | done | #44, `AccessFilterSpec`, `SearchApiSpec` | |
| US-09.1 | DMS-53 | "Valid on" search | done | #43, `ValidAtApiSpec` (with CSV export) | |
| US-05.1 | DMS-54 | Compare versions | done | #45, ADR 0033, `VersionCompareApiSpec` | |
| US-07.1 | DMS-55 | Review task and recertification | done | #47, ADR 0030, `ReviewApiSpec` | Notified and escalated since DMS-141 (ADR 0057) |
| US-08.1 | DMS-56 | Legal hold | done | #48, ADR 0031, 0047, `LegalHoldApiSpec` | |
| US-12.1 | DMS-57 | Evidence report | done | #51, ADR 0034, `EvidenceReportApiSpec` | |
| US-14.1 | DMS-58 | Stable address | done | #52, ADR 0035, `CurrentDocumentApiSpec` | |
| US-15.1 | DMS-59 | Compliance cockpit | done | #50, ADR 0036, `ComplianceApiSpec` | |

## Open follow-ups

The 14 follow-ups still open in Jira, each checked criterion by criterion against the code. Later work covered parts of them, but no issue is finished.

| Issue | Covered by later work | Still open | For stage 1 |
|---|---|---|---|
| DMS-81 Version model | Owner confirmation of minor versions (#32), retention rule from the class (DMS-133, #82) | Language and overlapping scopes in the validity key | Defer languages; decide an operating rule for scopes (see below) |
| DMS-84 Deviation UI | Locked sections, requesting and withdrawing, the report, own error code at submission (#98, ADR 0069) | Changing the text of a locked section in the UI, which DMS-84 does not ask for | Decide: without it a request changes nothing in the UI |
| DMS-85 Test mode hints | Solved by #94 | Rest in DMS-150 | Defer: the release block prevents wrong output |
| DMS-88 Workflow extensions | My tasks, votes, workflow status in the UI (#38); deputyships instead of per-task delegation (DMS-49, 99, 105) | Review modes, metadata conditions, parent owner, per-task delegation, four-eyes switch for the approval, workflow configuration UI | Defer; configure the workflow of the class through the API |
| DMS-89 Partner master | | Everything; the profile warns about `demo` | Defer to stage 2 |
| DMS-91 Document page | Display names (#56), version history, rendered versions, votes on the page (#38, #57), no workflow query per document (this branch) | Paged list with filters (search and library cover finding documents), partner id, reload button on 409 | Defer; close the list point by decision |
| DMS-92 Publication | Notice on a dead publication job, with a retry (DMS-142); withdrawing an approved version (DMS-143) | Time zone endpoint, editorial settings, overlapping scopes | Defer |
| DMS-102 Search | Notifications as outbox subscriber (#79); Word text (#63); basic auth, https and replicas are configurable, the profile now requires them | Clause text, PDF and OCR text, a later subscriber misses earlier events, owner change and version time | Defer, except PDF text if policies are uploaded as PDFs |
| DMS-104 Review | Notification and escalation (DMS-141, ADR 0057); inheritance, recalculation, backfill (#99, ADR 0068) | Rollout notice, settings in the UI, notice of moved dates: DMS-153 | Announce the rollout before going live |
| DMS-108 Report and cockpit | Owner UI for the name list (#71), display names in the UI, purpose in the request body (DMS-144); verified prefix of the chain with a daily full verification, report list and retention, cockpit notices, export and figures, names in the PDF (ADR 0077) | Uncovered regulatory requirements (no regulatory mapping yet), telling someone of a violation the daily verification found | Defer |
| DMS-118 On-demand PDFs | Limits per user and tenant, plain draft PDFs (ADR 0073) | Limits on Word exports and previews, plain copies | Defer the rest: `draft.pdf` stayed fast in the load test |
| DMS-124 Office fields | | Everything | Defer |
| DMS-125 People picker | Service user described in ADR 0037 and `docs/operations/production.md` | Fuzzy search, paging, hiding chosen people | Defer |
| DMS-132 Retention | Rules from the class (#82) | Jurisdiction and contract-end trigger, simulation, withdrawn drafts, personal data in the audit trail, orphans at scale | Defer; decide the audit data question with data protection |

The check of these issues was done by reading the code, not by running each criterion; its main findings about the blockers were verified separately.

## What blocks an MVP release

The first production stage covers policies and guidelines; contracts follow in stage 2 (business concept 12.1). That decides what blocks.

### Features

| # | Issue | Why it blocks |
|---|---|---|
| 1 | DMS-104 | Solved by DMS-141 (ADR 0057). Was: nobody is notified of a review task or its overdue date. Periodic review is the core compliance promise for policies, and an owner who is told nothing misses it silently |
| 2 | DMS-92, US-06.1 | Solved by DMS-142 (ADR 0058): a rendition or publication that failed for good now notifies owner and records managers |
| 3 | DMS-92 | Solved by DMS-143 (ADR 0059): an approved version can be withdrawn with a reason before its valid-from date. Follow-ups: DMS-145 |
| 4 | DMS-108 | Solved by DMS-144 (ADR 0060): purpose and reason travel in the request body |

### Before going live (operations)

- Deployment: the production image and environment have not been built or tried. Everything so far ran locally.
- OpenSearch with TLS, credentials and replicas; ClamAV; a bound TSA with its certificates; an SMTP relay; a user directory token per tenant organization. The production profile refuses or warns about each of them.
- Multi-factor authentication for authors, approvers and administrators (business concept 14.1) is ZITADEL configuration and has to be set and verified.
- Backup and restore rehearsed against the targets of 15 minutes data loss and 4 hours downtime (`docs/operations/production.md`). Only dump and restore of the database have been exercised.
- `docs/operations/production.md` worked through for the environment, and the load test repeated there with production sizing.

All of this is tracked in DMS-139. The load test added DMS-136 (tenant quotas), DMS-137 (background jobs at full volume) and DMS-138 (approver's inbox, index migrations); they are not in the table of follow-ups above.

### Decisions needed

- Do policies use validity scopes that can overlap (`DE` and `AT|DE`)? If so DMS-81 blocks; otherwise an operating rule is enough.
- Are policies uploaded as PDFs? Their text is not searchable yet (DMS-102).
- Personal data in audit entries: does data protection accept the current state for stage 1 (DMS-132)?
- Is the first class configured once through the API, or is the workflow configuration UI needed (DMS-88)?

## Load test

Reduced by 1:100 (20,000 documents, 98,000 versions, 970,000 acknowledgements, 1,000 users, 50 sessions acting about once per second, one tenant). Own stack, not the development stack. Method, volume model and limits: [scripts/loadtest/README.md](../../scripts/loadtest/README.md).

| Run | Build | Requests | Errors | Requests/s |
|---|---|---:|---:|---:|
| 01-baseline | main at 46347af, without V38 | 240 | 240 | 0.9 |
| 02-indexes-only | main at 46347af, with the indexes of V38 | 1,685 | 4 | 12.4 |
| 03-after | this branch, one publication per 30 s | 19,724 | 0 | 63.9 |
| 04-stress-publications | this branch, publications without limit | 11,713 | 0 | 61.6 |

Latencies in milliseconds:

| Operation | Baseline p95 | Indexes only p95 | After p50 | After p95 | After p99 | Stress p95 |
|---|---:|---:|---:|---:|---:|---:|
| Library | failed | 12,288 | 57 | 95 | 137 | 167 |
| Library, filtered | failed | 13,906 | 54 | 92 | 136 | 188 |
| Document list | failed | 11,332 | 27 | 131 | 198 | 183 |
| Search with facets | failed | 6,502 | 8 | 30 | 45 | 52 |
| Document view | – | 4,436 | 9 | 25 | 56 | 63 |
| Document content (HTML) | – | 5,502 | 10 | 27 | 51 | 68 |
| Live preview | – | – | 4 | 9 | 19 | 34 |
| Draft save | – | 7,525 | 21 | 53 | 95 | 143 |
| Submit | – | 6,049 | 54 | 136 | 183 | 292 |
| Approver inbox | failed | 11,387 | 450 | 780 | 982 | 41 |
| Approve | – | 6,726 | 58 | 80 | 80 | 212 |
| Open acknowledgements | failed | 7,401 | 21 | 100 | 140 | 257 |
| Acknowledge | – | 4,720 | 17 | 40 | 73 | 110 |
| Upload attachment | – | 7,381 | 37 | 83 | 140 | 173 |
| Upload Word | – | 8,063 | 27 | 68 | 123 | 174 |
| PDF rendition | – | 5,503 | 11 | 31 | 54 | 73 |
| Draft PDF (on demand) | – | – | 212 | 341 | 535 | 578 |
| PDF preview of a Word file (conversion) | – | 9,661 | 1,256 | 2,543 | 3,125 | 3,159 |
| Approval to published (asynchronous) | – | 120,975 | 6,350 | 8,617 | 8,617 | 46,038 |

The code as it was on `main` collapsed under this load: every request failed. With the fixes (ADR 0056) a five-minute run had no errors, and with publications unlimited none either.

## Non-functional requirements

Targets of chapter 13 of the business concept as far as this report can say something about them.

| Requirement | Target | Measured at 1:100 | Assessment |
|---|---|---|---|
| Page of a document | p95 ≤ 1.5 s | API calls of the page 25–31 ms p95, without browser | Met for the API; not measured in a browser |
| Search with facets | p95 ≤ 2 s at 10 million versions | 30 ms p95 at 98,000 versions | Met at this volume; 10 million were not tested |
| Live preview (US-03.2) | p95 ≤ 2 s | 9 ms p95 in the API | Met for the API |
| Rendering up to 50 pages | p95 ≤ 10 s | Approval to published 8.6 s p95, with one-page documents | Not proven for 50 pages |
| Acknowledgement peak (14.2) | 100,000 in 48 h, that is 0.6 per second | 5 per second at 40 ms p95, at 1:100 data | Met with a wide margin |
| Data loss, downtime | ≤ 15 minutes, ≤ 4 hours | Not rehearsed | Open, see above |

Not assessed: availability, accessibility (WCAG 2.2 AA, accessible PDF), use on mobile devices, per-tenant encryption keys, export of all data per tenant, configuration export between environments, tenant quotas and throttling (14.2). Rate limiting per tenant does not exist; the production checklist asks for it at the reverse proxy per client.

## Checks

Run on 2026-11-04 on this branch.

| Check | Result |
|---|---|
| `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test` | Passed: 1,030 tests, no failures, warnings fail the build |
| Frontend lint, typecheck, format, tests, build | This branch does not change the frontend; the pull request's CI runs them |
| `scripts/dependency_audit.py` | 220 libraries checked. One finding: `spring-core` 7.0.3, GHSA-659m-px2c-25wj (low), brought along by Flowable |
| `npm audit --omit=dev` | 6 findings (2 low, 4 moderate). The ones looked at come from `ts-morph` and `minimatch` through the `shadcn` package; the offered fix is a breaking change of it. Not assessed one by one (DMS-139) |

After the four blockers, on all of them together: 1,079 backend and 405 frontend tests passed. On the running local stack: withdrawing a version in the browser as an approver (status, reason, 409 on a second try, absent from library and search, mail to the owner), purpose and reason in the body (403 without them, 405 for the old GET), and a stopped rendering service with the job set to dead by hand (mail to owner and records manager, retry through the API, version approved), and an overdue review task added by hand with a 30-second deadline check (one mail to the owner, one notice, no repeat). Escalation of a review task ran in tests only.

Not run for this report: other browser walkthroughs, backup and restore, the production profile in a real environment.
