# 0076: Workers without outbound network behind a relay, limits on reading Office files, tenant field formats on RE2

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-160) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

DMS-160 collects three findings of a security review. Each was checked against the code first.

1. **The conversion worker loads what documents name.** `POST /api/v1/uploads/preview.pdf` hands a file to LibreOffice. README and docstring promised no outbound network, but `compose.yaml` defined none and the production checklist only limited inbound traffic. Measured again on the pinned LibreOffice 25.2.3.2 with the worker image and a listener on the same network:

| Reference in the document | Worker before | With `BlockUntrustedRefererLinks=true` |
|---|---|---|
| Image over http: HTML `<img>` (PDF and DOCX target), ODT `draw:image`, DOCX `r:link` | fetched | not fetched |
| Image through `file://`: the same three | embedded into the PDF | not embedded |
| HTML stylesheet over http (`<link>`, PDF and DOCX target) | fetched (HEAD and GET) | **still fetched** |
| Image in a `data:` URI | embedded | embedded |
| ODT `text:section-source` (http, `file://`), ODS `WEBSERVICE`, ODS external reference to `file://` | not loaded | not loaded |

2. **One small file can take the memory of all tenants.** `OfficePackage` allowed 256 MiB per file, all of it parsed into a DOM, on a thread that runs requests. A 389 KB DOCX retained 1.67 GB. Three more things came up:
   - `OutOfMemoryError` and `StackOverflowError` are `VirtualMachineError`s, which ZIO treats as fatal: it ends the process. They must not reach ZIO.
   - A limit per part does not help: a file may have many parts, and all are parsed and held at once.
   - The DOM costs more the denser the XML is. Measured for 32 MiB of XML after walking it: 193 MiB for ordinary paragraphs, 482 MiB with many attributes, 746 MiB for `<b/>` repeated.

3. **Tenant-defined field formats run on a backtracking engine inside `db.transact`.** `(.*a){12}b` took 4.4 s at 30 characters. `POST /api/v1/document-classes/{id}/validate` had no role check. The frontend does not call it; only tests and API clients do. Two more things came up:
   - **RE2 alone does not close this.** It expands counted repetition: `(.{1,1000}){1,1000}` compiles and took 22 s on 1000 characters with RE2J 1.8. Its time is the size of the pattern times the length of the value, so the size must be limited as well, and RE2J does not expose it.
   - `[a-z&&[^b]]` compiles on RE2J but means something else, without an error.

Given: formats live in field definitions, of which classes pin versions. Documents stay on their profile version, and class versions are append-only. RE2J 1.8 was on the class path through CEL, only for `templates-core`. The application runs on the host in development, and Docker publishes no ports of a container that is only on internal networks (tried: no answer on the published port). The application cannot see the network of a worker.

## Decision

### Workers

1. **Both workers go on the network `workers` with `internal: true`.** The rendering worker refuses references itself (`NoFetching`); the network is its second line.
2. **`worker-gateway` is the way in:** nginx as plain TCP relay (`docker/worker-gateway/nginx.conf`), in both networks, publishing 9180 and 9181. The application keeps its URLs. Names are resolved per connection, because a recreated worker gets a new address. `dev-setup.sh` and the load test start the gateway, which starts the workers.
3. **Every LibreOffice profile is seeded** with `BlockUntrustedRefererLinks=true`, in `user/registrymodifications.xcu`, before the start. `WORKER_VERSION` becomes 2.
4. **HTML is not accepted as an upload any more** (`ConvertibleExtensions` without `html`, `htm`; `file_not_convertible`). Stylesheets cannot be stopped in LibreOffice, and an HTML upload has no use the other formats lack. The worker keeps converting HTML: the Word export of the platform's own pages needs it, and HTML originals uploaded before stay readable.
5. **`dms.conversion.outboundBlocked`** (`DMS_CONVERSION_OUTBOUND_BLOCKED`, default false) is how the operator states the network rule. The profile `production` refuses false. The checklist has a mandatory item with a command that tries it. The setting is a statement, not a measurement, and the checklist says so.

### Office files

6. **Limits in `OfficePackage`:**
   - 32 MiB for all parts parsed at once, not per part. Parts that are only copied, such as images, do not count.
   - At most 200 times the compressed size per part, after the first 8 MiB. Office XML normally stays well below that, deflate ends at about 1000. The check stops the unpacking, so the reported file costs 8 MiB instead of 256 MiB.
   - At most 500 levels of nesting, with the JDK's own parser (`newDefaultInstance`), because the code walks the DOM recursively.
   - 256 MiB and 2000 entries per package stay.
7. **`OfficeGate` is the only way to read a file:** a semaphore with one permit per 2 GiB of heap, from 1 to 4, in `attemptBlocking`. Worst case per file is about 1 GiB, so at most half the heap goes to files. It is a single gate per process, since memory is too. It needs no `ZLayer`, because it holds no configuration.
8. **`OutOfMemoryError` and `StackOverflowError` become a refused file** in `OfficePackage.attempt`, inside the blocking thunk.
9. **The refusal is typed.** `OfficeProblem` is `Unreadable` or `TooComplex` instead of a string. The error code is `office_file_too_complex` (422) on every path, the text is in the catalogs (`upload.file.tooComplex`), and `requestError` uses it wherever a call fails. The rendering job fails with the new code `too_complex`, explained in the rendition status and the failure notice. Search indexes the name only, as for other failures.

### Field formats

10. **`FieldPattern` runs formats on RE2J** (explicit dependency of `documents-api`). A value of up to 1000 characters is checked. A longer one gets `out_of_range`, only on fields with a format.
11. **A format can be saved if it means the same on both engines:** it compiles on `java.util.regex` and on RE2J, uses no `&&` in a class, and is at most 2000 characters with counted repetition expanded. Size and value length together keep one value below about 50 ms. The size is counted by our own small walk over the pattern, which only has to be right about repetition, and errs on the high side.
12. **Saving a class is refused** while its effective profile, inherited fields included, has a format that could not be saved today. The message names field and version. The way out is a new field version.
13. **Formats saved before keep their meaning.** Documents stay on their version, and nobody could correct them. Whatever cannot run on RE2 runs on `java.util.regex` with a limit of one million characters read per value. Past the limit, or on a stack overflow, the value does not match.
14. **`validate` requires the author, owner or reviewer role for the class,** the roles of `EditDraft`, whatever the unit, since a document may not exist yet. This follows `UploadFlowApi.mayCreateIn`. Tenant administrators do not get it from their role.

## Alternatives

### Reaching workers on an internal network

| | Relay in both networks (chosen) | Workers in both networks | Application as a container | No `internal`, firewall rules |
|---|---|---|---|---|
| Worker has outbound access | No | **Yes**, defeats the purpose | No | No, if the rules hold |
| Application keeps running on the host | Yes | Yes | No | Yes |
| Works the same everywhere | Yes | Yes | Yes | No, host specific |
| Cost | One small service | None | Development setup changes | Rules outside the repository |

### Refusing a production start without the network rule

| | Stated setting (chosen) | Worker reports its routes in `/health` | Worker tries an outside address | Checklist only |
|---|---|---|---|---|
| Sees reality | No | In part: a NetworkPolicy leaves the route in place, so it would refuse correct setups | Yes, but it sends traffic from the worker, and a dead address proves nothing | No |
| Works if the worker starts later | Yes | No | No | Yes |
| Stops a forgotten rule | Yes, someone has to confirm it | Mostly | Mostly | No |

### Formats that RE2 cannot run

| | `java.util.regex` with a limit on steps (chosen) | Skip the check | Report an issue | Migrate the definitions |
|---|---|---|---|---|
| Existing documents keep their rules | Yes | No, invalid values pass | Yes, but they can never be submitted | Changes what was released |
| Backtracking engine still in use | Only for old formats | No | No | No |
| Code | About 15 lines | None | Small | Rewriting patterns is not automatic |

### Other points

- **HTML keeps being accepted.** Then the only protection against stylesheets would be the network rule. Refused for a format with no known use; a page can be saved as PDF.
- **Streaming parser (StAX) for finding fields.** It would help the scan only. Filling still needs the DOM, so a file that can be scanned but never filled would only fail later. Not simple, so not done.
- **A limit on the time of a match.** RE2J cannot be interrupted, and the rule against forked fibers inside a transaction excludes a watchdog.
- **A new `Action` in the policy for `validate`.** `Policy.decide` needs a unit on the resource, which does not exist before the document does, so unit-restricted authors would lose access.
- **A setting for the limits.** Constants, as the existing limits are; a higher limit is a decision about heap and belongs in a release.

## Consequences

### Positive

- A document cannot reach the company network or the internet through the worker, and the promise in the README is true.
- `file://` no longer puts files of the worker into a PDF, independent of the network.
- A small file costs at most what its size justifies, and a refused file does not end the process.
- A format cannot hold a thread beyond a known bound, and the tenant gets a clear message when saving.

### Negative

- **Production does not start** after this update until the operator sets `DMS_CONVERSION_OUTBOUND_BLOCKED=true`. This is on purpose.
- The workers no longer start on their own ports: `docker compose up -d conversion` publishes nothing. Use `worker-gateway`, which needs both workers, so a development setup now always builds the conversion image.
- HTML files can no longer be uploaded as originals.
- **Spreadsheets with more than 32 MiB of XML in their sheets and shared strings are refused.** That is about a million cells, so a large workbook of 50 MB can hit it. It was accepted before, at a cost of over 1 GiB of heap. Word documents and presentations are far below it.
- **Stored originals above the limit can no longer be filled.** Their renditions, previews and filled files fail with `too_complex`. Their fields are all that needs parsing, so originals without fields are not affected. How many exist cannot be told without reading them.
- Saving a class is blocked while it binds an old format, also for an unrelated change.
- Existing values over 1000 characters in a field with a format no longer pass.
- A rare difference from before stays for formats RE2 runs: `(?i)` folds Unicode, and `.` accepts `\r`.
- Waiting for the gate holds a database connection where the caller is in a transaction (the search indexer). The work inside needs no connection, so this cannot deadlock.
- The cost of checks grows with the request: about 44 µs per character at worst, per field with a format of the largest size. Requests stay limited by the body size only.
- `java.util.regex` stays for old formats. Patterns that loop without reading a character are not counted by the limit.

## Follow-ups

- Find the stored formats that RE2 cannot run, and the originals above the limit, so that tenants can be told before they run into them.
- Limit the size of workbooks that are not filled, or read their fields with a streaming parser, if large spreadsheets matter.
- The frontend runs formats in the browser with `RegExp` (`field-values.ts`): a bad format still freezes the tab of the person typing. Check there with the backend, or not at all.
- A fair share of the gate per tenant: one tenant can still keep others waiting, though no longer out of memory.
- Check the network rule from inside the platform, if an installation can offer a probe address.

## Reversibility

Mostly easy. The limits, the gate and the role check are code without stored state; no migration is involved. `html` and `htm` come back with one line each, the network with `compose.yaml`. The seeded profile is a constant, and removing it needs a new worker version. The field definitions saved under the new rules are valid on the old engine too, by decision 11, so going back needs no data change. The one thing that does not return by itself is the operators' setting, which then has no effect.
