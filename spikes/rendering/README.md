# Spike DMS-23: deterministic PDF/A rendering with WeasyPrint

Time-boxed technical spike for [ADR 0008](../../docs/adr/0008-rendering-weasyprint.md). Minimal code, not production quality.
Question: does HTML → PDF/A rendering with WeasyPrint in a Python worker produce **valid PDF/A** and is it **deterministic**, so that a
released document version can store a rendition plus checksum and a later re-render reproduces it?

## TL;DR

| Topic | Result |
|---|---|
| Recommendation | **Accept ADR 0008.** WeasyPrint 70.0 in a digest-pinned container produces valid PDF/A-2b and PDF/A-3b and byte-identical output once the worker stops feeding the clock into the PDF. |
| PDF/A | All 3 samples × 2 flavours pass veraPDF 1.30.2 with 0 failed checks. Fonts are embedded as subsets. |
| Determinism | 20 renders per sample and flavour (10 per container, 2 fresh containers): **1 distinct SHA-256 each**. Also identical with a random hash seed, different TZ, locale, CPU and memory limit, and after a `--no-cache` image rebuild. |
| Non-determinism found | Only timestamps: Info/XMP dates when set from the clock, embedded-file dates in PDF/A-3 (WeasyPrint defaults to `datetime.now()`), and the `/ID` (MD5 of the content, so it follows the dates). The layout engine, font subsetting and compression are deterministic. |
| Fix | Dates = release timestamp of the version; embedded-file dates set explicitly; `/ID` = hash(tenant id, document id, version). |
| Speed / size | 0.25–0.36 s per 2-page document, 1.0–1.2 s for 12 pages (in-process); 26–31 KB and 70 KB. |
| PDF/UA | Tagging works: PDF/A-2a/3a + PDF/UA-1 in the same file passes the veraPDF PDF/UA-1 machine checks. Manual review still needed. |

## Run

Prerequisites: Docker. Everything runs in containers, without network access during rendering.

```bash
cd spikes/rendering
./run-all.sh                     # build image, all determinism runs, veraPDF validation (~10–15 min)

docker build -t dms-render-spike:dms-23 .
./determinism.sh deterministic 10   # or: default | naive; writes results/determinism-<mode>.txt
./validate.sh                       # veraPDF reports to results/verapdf/, summary in results/verapdf-summary.txt
python3 pdfdiff.py a.pdf b.pdf      # show which PDF objects differ between two renders
```

Single render (the job file is the input contract, see below):

```bash
docker run --rm --network none -v ./samples:/work/samples:ro -v ./out:/work/out --user "$(id -u):$(id -g)" \
  dms-render-spike:dms-23 samples/contract/job.json out/contract.pdf [--variant pdf/a-3b] [--mode deterministic] [--pdfua]
```

Files:

| File | Purpose |
|---|---|
| `render.py` | Worker: job JSON + HTML + shared CSS → PDF/A, prints one JSON result line (pages, bytes, ms, sha256, renderer). Modes `default`, `naive`, `deterministic`. |
| `templates/base.css` | Print stylesheet: A4, header/footer margin boxes, tables, clause numbering (`counters()`), checkboxes. Header/footer text is generated per job. |
| `Dockerfile`, `requirements.txt` | Base image pinned by digest, Debian packages pinned by version, Python packages pinned with hashes (`--require-hashes`). |
| `determinism.sh` | Renders each sample in PDF/A-2b and PDF/A-3b 10× in container A and 10× in a fresh container B, compares SHA-256. `B_ARGS` varies container B. |
| `validate.sh` | veraPDF (official `verapdf/cli` image, pinned by digest) against the declared flavour; also renders and checks the tagged variants. |
| `pdfdiff.py` | Crude object-level diff (inflates Flate streams) to locate differences. Only sees top-level objects, not those inside object streams. |
| `samples/{policy,contract,form}/` | `job.json` + `content.html`: 2-page policy (PDF/A-2b), 12-page contract with tables, numbered clauses and 3 annexes (PDF/A-3b), form with tables and checkboxes (PDF/A-2b). |
| `results/` | Evidence: determinism tables, timings (JSON lines), veraPDF MRR reports, diffs, rebuild check. PDFs are not committed (`out/` is ignored). |

## Versions

| Component | Version | Pinned how |
|---|---|---|
| Base image | `python:3.13-slim-trixie` (Debian 13, CPython 3.13) | `@sha256:b3575dcf816cdacc5292df77a6e3e59b8941631078373a045efa2ac1e9c5c3b2` |
| WeasyPrint | 70.0 (newest on PyPI on 2026-10-28) | `requirements.txt` with hash |
| pydyf | 0.13.0 | ditto; also fonttools 4.68.0, tinycss2 1.5.1, tinyhtml5 2.1.0, Pillow 13.0.0, … |
| Pango / HarfBuzz / FreeType / fontconfig | 1.56.3-1 / 10.2.0-1+deb13u1 / 2.13.3+dfsg-1+deb13u1 / 2.15.0-2.3 | apt `pkg=version` |
| Fonts | `fonts-noto-core` 20201225-2 (Noto Sans, Noto Serif) — the only fonts in the image | apt `pkg=version` |
| veraPDF | CLI 1.30.2 | `verapdf/cli:v1.30.2@sha256:d5ee329657cf9bc4b2400392dd54c7d0a0ce9980ff6fa2da5590eebeec007cdb` |

## Results per sample

Deterministic mode, image `sha256:44575b34…`. Time = layout + PDF writing inside the worker process (min–median–max over 20 renders);
the whole process including Python start-up and imports takes about 0.7 s (policy) and 1.7 s (contract). Measured on a developer laptop.

| Sample | Pages | Flavour | Valid PDF/A (veraPDF) | Deterministic (20 renders, 2 containers) | Time | Size |
|---|---|---|---|---|---|---|
| Policy | 2 | PDF/A-2b | yes (144 rules, 0 failed) | yes, 1 hash `c071225c…` | 274–287–339 ms | 30.1 KB |
| Policy | 2 | PDF/A-3b | yes (146 rules, 0 failed) | yes, 1 hash `32c5eb5b…` | 275–292–333 ms | 30.7 KB |
| Contract | 12 | PDF/A-2b | yes | yes, 1 hash `e3851a5e…` | 1024–1084–1194 ms | 69.4 KB |
| Contract | 12 | PDF/A-3b (with embedded source JSON) | yes | yes, 1 hash `e980aed0…` | 1005–1066–1237 ms | 70.0 KB |
| Form | 2 | PDF/A-2b | yes | yes, 1 hash `b2c75470…` | 255–293–364 ms | 25.6 KB |
| Form | 2 | PDF/A-3b | yes | yes, 1 hash `24965d4c…` | 255–279–325 ms | 26.2 KB |

Tagged variants (`validate.sh`): policy `pdf/ua-1` passes UA-1; policy `pdf/a-2a` passes 2a; contract `pdf/a-3a --pdfua` (97.6 KB) and form
`pdf/a-2a --pdfua` pass both their PDF/A flavour and UA-1. Tagged renders are deterministic as well (checked with 2 containers each).

Robustness checks (all produced the same hashes as the table above):

- Container B with `PYTHONHASHSEED=random`, `TZ=Pacific/Auckland`, `LANG=de_DE.UTF-8`, `--cpus 1 --memory 512m` (`results/determinism-deterministic-varied-env.txt`).
- Image rebuilt with `docker build --no-cache` (different image id `3ebafc23…`): all six hashes identical (`results/rebuild-check.txt`).
- An earlier image build from the same sources at a different time also gave the same policy hash.

## Non-determinism: causes and fixes (before/after)

Three modes of `render.py`, each 20 renders per sample and flavour:

| Mode | What it does | PDF/A-2b | PDF/A-3b |
|---|---|---|---|
| `naive` | Creation/modification date = render time (what a straightforward integration does) | **20 distinct** hashes | **20 distinct** |
| `default` | WeasyPrint defaults: no dates in the metadata | 1 hash | **20 distinct** |
| `deterministic` | All values derived from the job | 1 hash | 1 hash |

Causes, located with `pdfdiff.py` (`results/pdfdiff.txt`):

| Cause | Where | Fix in `render.py` |
|---|---|---|
| Creation/modification date from the clock | Info `/CreationDate`, `/ModDate`; XMP `xmp:CreateDate`, `xmp:ModifyDate` | `dcterms.created`/`modified` = `released_at` of the version, normalised to UTC with second precision |
| Embedded-file dates (PDF/A-3 associated file) | `/Params <</CreationDate … /ModDate …>>` of the embedded file stream; WeasyPrint uses `datetime.now()` (or file ctime/mtime) when not given | `Attachment(created=…, modified=…)` = release timestamp |
| File identifier | Trailer `/ID`: WeasyPrint passes `pdf_identifier=True` for PDF/A, pydyf then uses the MD5 of the content, which changes with every date | `pdf_identifier` = first 32 hex chars of SHA-256(`tenant_id:document_id:version`) — stable and meaningful |

Checked and **not** a cause: font subset tags (`ABCDEF+Noto-Sans`; WeasyPrint derives them from an MD5 of the font description), XMP
`xmpMM:DocumentID` (only generated with a random UUID for PDF/X-4, not for PDF/A), Flate compression (zlib, same level, same input), object
order, hash randomisation, time zone and locale. `SOURCE_DATE_EPOCH` is not read by WeasyPrint and is not needed.

## Metadata

- Title, author, subject (description), keywords (document id, version), creator (worker + template version) via HTML `<meta>`; WeasyPrint
  writes them to the Info dictionary and XMP consistently, as PDF/A requires.
- Custom XMP properties `dms:documentId`, `dms:version`, `dms:validFrom`, `dms:renderer` with a `pdfaExtension` schema declaration.
- **Pitfall:** WeasyPrint's `xmp_metadata` option appends a *second* `rdf:RDF` element. veraPDF ignores it: PDF/A passed although the
  properties were effectively invisible, and a PDF/UA identification placed there was reported missing. `render.py` therefore wraps
  `DocumentMetadata.generate_rdf_metadata` and merges its descriptions into WeasyPrint's single `rdf:RDF`. Once merged, veraPDF checks them
  (an undeclared `pdfuaid` schema immediately failed PDF/A-3a clause 6.6.2.3.1 until the extension schema was added).
- Header: title | document id · version. Footer: "Valid from …" | "Page x of y" (`counter(page)`/`counter(pages)`).

## PDF/UA (brief check)

- WeasyPrint 70 has `pdf_tags`, variants `pdf/a-1a/2a/3a` (tagged PDF/A) and `pdf/ua-1`, `pdf/ua-2`. Margin boxes (header/footer) are
  marked as artifacts; the catalog gets `MarkInfo` and `DisplayDocTitle`; `lang` comes from `<html lang>`.
- `pdf_variant` takes only one value, but PDF/A-2a/3a plus a PDF/UA-1 identification in XMP (`--pdfua`) passes both the PDF/A-3a and the
  PDF/UA-1 veraPDF profiles (contract: 106 UA rules, 0 failed).
- Structure tree of the contract: `Document`, `H1`/`H2`, `P`, `L`/`LI`/`LBody`, `Table`/`THead`/`TBody`/`TR`/`TH`/`TD`. Weak spots: no
  `Lbl` for the clause numbers (they are CSS `::before` content inside `LBody`), many `Span`/`NonStruct` wrappers, no explicit header scope.
- veraPDF covers only the machine-checkable Matterhorn conditions. A manual review (e.g. with PAC) is needed before claiming PDF/UA.
- Tagging increases size (contract 70 → 98 KB) and does not affect determinism.

## Recommendation

**Accept ADR 0008** (WeasyPrint in a Python worker, veraPDF validation), with PDF/A-2b as the default flavour and PDF/A-3b where the
machine-readable source is embedded.

Positive:

- Valid PDF/A out of the box for realistic documents (tables, running headers, page x of y, numbered clauses, form layout).
- Byte-identical re-renders are achievable with little code; the stored SHA-256 can serve as the rendition checksum and a re-render can
  prove it.
- Fast enough for the job-queue model (≈ 0.3 s per short document, ≈ 1 s per 12 pages, single core); small files.
- Tagged PDF and PDF/UA-1 machine conformance are possible without changing the renderer.

Negative / cost:

- Determinism depends on the **whole image**: WeasyPrint, Pango/HarfBuzz, fonts and templates. Any upgrade changes bytes (expected), so
  re-render verification only works with the archived image digest. Old images must be kept as long as re-renders must be proven.
- Debian packages can only be pinned by version; once a security update replaces them, the Dockerfile no longer builds unchanged.
  Needs `snapshot.debian.org` or keeping built images by digest.
- Custom XMP needs a small wrapper around a WeasyPrint internal (`generate_rdf_metadata`); it needs a regression test on upgrades.
- veraPDF is a JVM process (≈ 1–2 s start-up per call in the container); validation costs more than rendering.
- PDF/UA quality needs manual review and probably template work (list labels, table headers).

## What the production worker needs

Job contract with the backend (job table and `SELECT … FOR UPDATE SKIP LOCKED`, ADR 0010). Input (job payload), as in `samples/*/job.json`:

```jsonc
{
  "job_id": "2f0c…",                          // idempotency key (ADR 0010)
  "tenant_id": "7c1e2f0a-…",
  "document_id": "CTR-2026-0318",
  "version": 2,
  "title": "IT Service Agreement",
  "author": "Legal Department",
  "description": "…",
  "lang": "en",
  "valid_from": "2027-01-01",
  "released_at": "2026-10-24T11:17:58+02:00",  // single source for every date in the PDF
  "pdf_variant": "pdf/a-2b",                   // or pdf/a-3b, pdf/a-2a + pdfua
  "content": "s3://…/renditions/input/<document>/<version>/content.html",
  "assets": ["s3://…/images/…"],             // fetched by the worker; no network access while rendering
  "template_version": "corporate-2026.1",
  "attachments": [{"name": "source.json", "uri": "s3://…", "relationship": "Source"}]
}
```

Output (written back to the job row / outbox event):

```jsonc
{
  "job_id": "2f0c…",
  "status": "succeeded",                       // or failed_validation / failed
  "object": "s3://…/renditions/<tenant>/<document>/<version>.pdf",   // S3 Object Lock (ADR 0009)
  "sha256": "e980aed0…",
  "bytes": 70034, "pages": 12,
  "pdf_variant": "pdf/a-3b",
  "validation": {"validator": "veraPDF 1.30.2", "profile": "PDF/A-3B", "compliant": true, "report": "s3://…/<version>.verapdf.xml"},
  "renderer": {"image": "registry/…/render-worker@sha256:…", "weasyprint": "70.0", "template_version": "corporate-2026.1"}
}
```

Requirements:

- Render only from the job: no clock, no random values, no network fetches during layout (custom URL fetcher that serves pre-fetched assets).
- Write the PDF only if veraPDF passes; store the MRR report next to it. Job fails with `failed_validation` otherwise (no retry loop).
- Record image digest and template version with the checksum in the audit trail; keep every image that produced an archived rendition.
- CI: golden-checksum test (these three samples or successors) on every image or template change; a changed hash must be an explicit decision.
- Run as non-root, `--network none` for the render step, read-only root filesystem (tested here), memory limit (512 MB was enough).
- Idempotency: re-running a job for the same `(tenant, document, version)` produces the same bytes, so the object write can be a
  conditional put / checksum comparison.
- Fonts come from the image only; the corporate font (if any) is installed in the image, never loaded from the input.

## Open risks / not covered

- Different CPU architectures (arm64) and other hosts were not tested; floating-point layout differences across architectures are possible.
  Pin the platform (`linux/amd64`) for archive renditions or test arm64 separately.
- Images (PNG/JPEG/SVG) and charts were not part of the samples; Pillow re-encoding must be checked for determinism.
- Very large documents (hundreds of pages), memory use and concurrent renders were not measured.
- PDF/UA was only checked by machine.
- The Debian package pinning will break on the next security update of a pinned package (see above).
