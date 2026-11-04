# Load test

A repeatable load test with a reduced volume model of section 14.2 of the business concept (DMS-60, [ADR 0056](../../docs/adr/0056-production-hardening-and-library-snapshot.md)).

It never touches the development stack: `compose.yaml` here is a compose project of its own (`dms-loadtest`), with its own volumes and ports (PostgreSQL 15433, S3 19100, OpenSearch 19201, rendering 19280, conversion 19281), and the backend runs on 18290. It does not need ZITADEL: `loadtest.py` holds an RSA key, serves its public part on 18295 as JWKS and mints the tokens of the load users, so the backend verifies real signatures. All state stays in `.local/loadtest/`.

## Running it

Needs Docker, Python 3, `openssl`, Java 21 and the images of the workers (`docker compose build rendering conversion`).

```bash
sbt app/stage                                       # the build under test
L="python3 scripts/loadtest/loadtest.py"
$L up                                               # services and migrations
$L start                                            # key server and backend
$L seed --reindex                                   # demo content, the volume, the search index (5 minutes)
$L snapshot                                         # keep the seeded database

$L run --label my-run --duration 300                # results in scripts/loadtest/results/my-run.json
$L queries                                          # where the database spent its time in that run

$L stop && $L restore && $L start                   # the same data again, for the next run
$L down --volumes                                   # remove everything
```

`restore` resets the database only. The search index then still knows documents of earlier runs and search hits lead to 404: run `seed --reindex` after `start` to compare runs exactly.

## Volume model

Factor 1:100 (`--factor`) of the large-group example:

| | Business concept 14.2 | This test | Note |
|---|---:|---:|---|
| Tenants | 50 | 1 | All documents in one tenant: half of an average tenant's 40,000 documents. Row-level security is active |
| Users | 100,000 | 1,000 | Role assignments in the database, plus 50 writers and approvers |
| Concurrent sessions | 5,000 | 50 | 40 readers, 8 authors, 2 approvers. See below |
| Documents | 2,000,000 | 20,000 | 85 % with a valid last version, 10 % also with an open draft, 5 % never released |
| Versions | 10,000,000 | 98,000 | Five per document, with their workflow cases, tasks, votes and approvals |
| Contents | 50 TB | not reproduced | The copies share the renditions of the demo documents: object store volume is not tested |
| Acknowledgements | 100,000 in 48 h at the peak | 970,000 stored | 5 % of the documents ask for one; every reader has confirmed them except for 30 |

Each virtual user acts about once per second (`--think`), far more than a person does, so 50 of them stand for several hundred real sessions. A tenant of the large-group example has 100 concurrent sessions on average. The volume is written by SQL as copies of real published documents, with triggers off (`seed`); everything during a run goes through the API.

**The mix.** Readers: library (plain and filtered), document list, search, document with HTML view, PDF rendition, open acknowledgements, opening and confirming them. Authors: create, preview and save a draft three times, draft PDF, attachment upload, submit; create a Word document, upload the file, PDF preview. Approvers: inbox and vote with step-up. Every approval leads to a rendition and a publication that notifies all of about 1,100 readers; `--publication-interval` paces them (30 s by default, which is still thousands of times the peak of 14.2; 0 removes the limit).

The first 60 seconds are not measured (`--warm-up`): after a start the JVM compiles the hot paths and single requests take up to 8 seconds.

## Results

Measured on one machine with everything on it (16 cores, 62 GB; other local projects were running): load generator, backend with 3 GB heap and 10 database connections, and all services.

<!-- RESULTS -->

## What the numbers do not say

- One backend instance, no reverse proxy, no TLS, no virus scanner, no email (notifications stay in the application).
- Short runs of a few minutes, so slow leaks and table growth do not show.
- Full volume is 100 times larger. What grows with the tenant is the library copy, the reminder check and everything listed under follow-ups in ADR 0056. A test at full volume needs a production-like environment.
