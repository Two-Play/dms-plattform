# 0066: The task inbox reads in batches and decides per task; indexes on filled tables are built by migrations without a transaction

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06; proposed 2026-11-05, implemented in DMS-138) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

DMS-138 takes up two follow-ups of ADR 0056.

**The inbox.** `GET /api/v1/workflow/tasks` took 450 ms p50 with 240 open tasks in the load test. The ticket names one document load per task, and there were more loops than that one. With 242 tasks the request ran 4,239 statements:

1. The filter of the API (`mayRead`) read each task's document with `DocumentService.get`: document, versions, attachments, files, profile and time zone.
2. `ApprovalWorkflow.myTasks` read the case and the active deputyships per case.
3. If a candidate of a case has a deputy, which is the normal case during a holiday, `views` also read the document per case through `DocumentGateway.subject`, the members of its access groups and the roles of each deputy. A batch of step 1 alone would have left this load in place.

`casesOf`, which ADR 0056 describes as a fixed number of queries, runs the same `views` per case, so the claim held for its tasks and votes but not for the deputies.

The filter is a permission check: a task of a version the reader may not see stays hidden, decided per document (`Policy.clearedFor`, DMS-101).

**Migrations.** V38 builds its indexes in a transaction, holding a lock that stops writers on the table. That is right on an empty production. Once there is data, an index needs `create index concurrently`, which PostgreSQL refuses inside a transaction. Trying it with the Flyway configuration of the time showed that a concurrent build never finished: Flyway keeps its advisory lock in a transaction that stays open during the migration, and the build waits for that transaction (`pg_stat_activity`: `create index concurrently` waiting on `virtualxid`, Flyway's connection `idle in transaction`). The migration waited for itself, with no timeout.

## Decision

**1. The inbox reads with a fixed number of queries, and decides per task as before.**

- `WorkflowApi.Context.mayRead` reads all documents with `DocumentService.getAll`, the read of the document list and the acknowledgements, and applies the unchanged `Policy.clearedFor` to each task's own version. The check stays in the application (ADR 0018), per request, with the caller's own subject.
- `ApprovalWorkflow.views` works on a list of cases: one read of deputyships, and for the cases with a deputy one read each of documents (`DocumentGateway.subjects`), group members (`ParticipantDirectory.clearedFor`) and roles (`heldRolesOf`). `Rules.taskDeputies` is still called per task. The single case goes through the same code, as do `cases` and `casesOf`, so there is one implementation of who may stand in.
- All reads run in the caller's `Db.transact(tenant)` and rely on row-level security as before. Nothing is cached.

Measured in `WorkflowApiSpec` (PostgreSQL in a test container, 242 tasks, half of them confidential, the approver has a deputy, 20 requests after 10 for warming up):

| | Statements, 4 tasks | Statements, 242 tasks | p50, approver | p50, deputy |
|---|---|---|---|---|
| Before | 74 | 4,239 | 928 ms | 1,002 ms |
| After | 23 | 23 | 124 ms | 107 ms |

The load test was not repeated; these numbers do not compare with its 450 ms.

**2. A task whose document cannot be read is hidden.** Before, it failed the whole request with 404. Foreign keys and the fact that documents are never deleted (they stay as tombstones, ADR 0054) make this unreachable, and hiding is the side that fails closed.

**3. Migrations without a transaction.** The convention is in `modules/db/README.md`; its parts:

- Flyway holds its advisory lock on the session (`setTransactionalLock(false)` in `Migrations.flyway`).
- An index on a table with data gets its own migration with exactly `drop index concurrently if exists` and `create index concurrently`, one index per migration. Flyway recognizes these statements and runs the file without a transaction; nothing needs to be declared.
- Statements Flyway does not recognize (for example `reindex index concurrently`) need a `V<n>__<name>.sql.conf` beside the migration with `executeInTransaction=false`.
- After a failed build the operator removes the failed row from `dms.flyway_schema_history` and runs `bin/migrate` again; the `drop` at the start clears the invalid index.

V38 stays as it is: changing an applied migration would break its checksum. No migration comes with this ADR: the batched reads use existing indexes, and V45 stays unused.

## Alternatives considered

| Criterion (inbox) | Chosen: batch all three reads, policy in the application | Batch `mayRead` only | Filter in SQL | Read only the tasks' versions, without content |
|---|---|---|---|---|
| Statements with 242 tasks | 23 | Hundreds with a deputy (`views` per case) | Fewest | 23 |
| Permission rule | `Policy.clearedFor`, unchanged | Unchanged | Second implementation of the rule | Unchanged, but on a second way to build the resource |
| Rows read | All versions of the documents, with content, twice if a deputy exists | Once more per case | Few | Few |
| Effort and risk | Small; three new batch methods on ports | Smallest | Large | Medium, a new projection of a version |

| Criterion (running without a transaction) | Chosen: Flyway's recognition, `.conf` only where needed | `.sql.conf` beside every such migration | `mixed = true` | Indexes outside Flyway, by a script of the operator |
|---|---|---|---|---|
| File with `create table` and a concurrent index | Refused by Flyway | Accepted: each statement commits on its own, a failure leaves half of it | Accepted, same result | Not applicable |
| Visible in the file tree | No, only in the statement | Yes | No | Not in the repository |
| Statements Flyway does not recognize | Fail loudly, then use `.conf` | Work | Fail | Work |
| History of the schema | Complete | Complete | Complete | Incomplete: environments drift |

| Criterion (Flyway's lock) | Chosen: lock on the session | Default: lock in a transaction | No lock, deployment guarantees one run |
|---|---|---|---|
| `create index concurrently` | Works | Never ends | Works |
| Two parallel runs | One waits (tested) | One waits | Both run |
| Transaction pooler between Flyway and the database | Must not be used: the lock would be lost | Works | Works |

| Criterion (after a failed build) | Chosen: documented removal of the failed row | `flyway repair` as a command of `bin/migrate` | Nothing, restore the backup |
|---|---|---|---|
| What changes | The one failed row | Failed rows, and it also realigns checksums of applied migrations | Everything since the backup |
| New code | None | A command, its documentation and its permissions | None |
| Risk | A manual statement on the history table | A checksum mismatch could be silenced in a regulated system | Data loss for a failed index |

## Consequences

### Positive

- The inbox no longer grows with the tasks in statements: 4,239 to 23 at 242 tasks, about seven times faster in the test.
- The document page and the document list gain too: `casesOf` no longer reads deputies per case.
- Who may see a task and who may stand in are decided by the same rules, per task; tests show a confidential task hidden between visible ones, for a candidate and for a deputy, and in another tenant.
- An index can be added to a filled table without stopping writers, by a procedure that is tested against the real configuration and from a jar, as `bin/migrate` runs it.

### Negative / Cost

- The inbox holds all versions of all its documents in memory for the request, with content, as the document list does. With a deputy they are read twice, by the workflow and by the API: two fixed sets of queries.
- Statements still grow with the distinct class versions among the tasks (one profile each, as in the document list), not with the tasks.
- `DocumentGateway`, `ParticipantDirectory`, `RoleAssignments` and `Groups` each gain a batch method next to the single one.
- A failed migration without a transaction leaves a failed row and possibly an invalid index, and needs an operator. Until then `bin/migrate` refuses to run.
- A concurrent build waits for every older transaction and reads the table twice: slower than a plain build, and `bin/migrate` can take long with a long-running transaction open.
- The migration runner must connect directly to PostgreSQL, not through a transaction pooler.
- Flyway's recognition of statements is a detail of its PostgreSQL parser. If an upgrade changed it, a migration fails loudly; `MigrationsSpec` shows it first.

## Follow-ups

- Should: rerun the load test and replace the inbox figure in `scripts/loadtest/README.md`.
- Should: read only the tasks' versions, without content, if the memory or the double read shows up in the load test.
- Could: `openTasksOf` scans the open tasks of the tenant for the candidate (`candidates @>`). It is one statement, but its cost grows with the tenant's open tasks; a GIN index would be the first migration under this convention, once a measurement asks for it.
- Could: page the inbox; the response still grows with the tasks.
- Could: mention the convention in the project conventions in `CLAUDE.md`.
- Ruled out: caching documents or decisions for the inbox; authorization stays per request (ADR 0056).

## Reversibility

**high**: The batch reads can be replaced by loops without any change of data or interface. The lock setting is one line and leaves no trace in the database. Migrations written under the convention stay valid either way: an index built concurrently is an ordinary index.
