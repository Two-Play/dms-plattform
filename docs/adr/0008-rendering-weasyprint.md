# 0008: Rendering HTML → PDF/A with WeasyPrint in the Python worker, validation with veraPDF

| Field | Value |
|---|---|
| Status | Accepted (spike DMS-23, 2026-10-28) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Approved documents are archived as PDF/A (audit-proof, 0009). The structured content model (0007) is transformed into HTML and must be printed with corporate design, headers/footers, page numbers and a table of contents. The same input document must produce a byte-identical or at least visually identical PDF. Accessibility (PDF/UA) is relevant for policies in the public sector.

## Decision

Rendering HTML → PDF/A with WeasyPrint in a separate Python worker that fetches jobs from the job table (0010). Every generated PDF is validated with veraPDF; only valid PDFs are archived. Accepted after the determinism spike DMS-23 (see "Spike result").

## Alternatives considered

| Criterion | Proposal: WeasyPrint | Headless Chromium | Typst | LibreOffice |
|---|---|---|---|---|
| PDF/A output | Native (PDF/A-1b to 4 depending on version) | No, post-processing required | Yes (PDF/A-2b among others) | Yes |
| PDF/UA / tagged PDF | Partial, tagging available | Tagged PDF, no PDF/UA | Partial | Yes |
| Print CSS (`@page`, page counters, running headers) | Very good | Limited | Own language instead of CSS | n/a (no HTML path) |
| Determinism | High, to be verified (timestamps, IDs) | Medium, depends on Chromium version | High | Low |
| Input | HTML/CSS from the model | HTML/CSS | Typst markup, own generator | DOCX/ODT |
| Operations | Python process, small container | Large image, high memory needs | Single binary | Large image, unstable under load |
| Run time | Medium | Fast | Very fast | Slow |

## Consequences

### Positive

- HTML/CSS as a single representation for the browser preview and the PDF.
- PDF/A without a conversion step; veraPDF ensures conformance.
- Workers can be scaled separately and are isolated from the JVM process.

### Negative / Cost

- A second runtime (Python) with its own build, dependency maintenance and image.
- WeasyPrint does not support JavaScript; charts must be generated as SVG beforehand.
- veraPDF is Java-based and adds another component to the worker.
- Browser preview and PDF may differ where print CSS is involved.

## Spike result (DMS-23, 2026-10-28)

Details, code and evidence: [`spikes/rendering/README.md`](../../spikes/rendering/README.md).

| Question | Result |
|---|---|
| Versions | WeasyPrint **70.0**, pydyf 0.13.0, Pango 1.56.3, HarfBuzz 10.2.0, fonts-noto-core 20201225-2, on `python:3.13-slim-trixie` pinned by digest; veraPDF CLI 1.30.2 (official image, pinned by digest). |
| PDF/A validity | **All outputs valid.** Policy (2 pages), contract (12 pages, tables, numbered clauses), form (tables, checkboxes) each pass veraPDF as PDF/A-2b and PDF/A-3b (0 failed checks). Fonts are embedded as subsets. |
| Determinism | **Byte-identical.** 20 renders per sample and flavour (10 in each of two fresh containers, the second with a random hash seed, other time zone, locale, CPU and memory limit): one SHA-256 per sample and flavour. Repeatable across an image rebuild. |
| Causes of non-determinism | Not in the layout engine. Only (a) creation/modification dates if taken from the clock (Info + XMP), (b) the dates of embedded files in PDF/A-3 (WeasyPrint falls back to `datetime.now()`), (c) the file `/ID`, which is an MD5 over the content and therefore follows (a) and (b). Font subset tags are MD5-based and stable. |
| Fix | Every date is the **release timestamp** of the version, embedded-file dates are set explicitly, `/ID` = hash of tenant id + document id + version. No `SOURCE_DATE_EPOCH` needed. |
| Metadata | Title, author, subject, keywords, creator via HTML `<meta>`; document id, version, valid-from date and renderer version as custom XMP properties with a PDF/A extension schema. WeasyPrint's `xmp_metadata` option adds a second `rdf:RDF` that veraPDF ignores, so the properties are merged into WeasyPrint's RDF (small wrapper). |
| Run time / size | 0.25–0.36 s for 2 pages, 1.0–1.2 s for 12 pages for layout and PDF writing (about 0.7 s and 1.7 s including Python start-up); 26–31 KB for 2 pages, 70 KB for 12 pages. |
| PDF/UA | Tagged output (`pdf/a-2a`, `pdf/a-3a`, `pdf/ua-1`) passes the veraPDF PDF/UA-1 machine checks; PDF/A-2a/3a and PDF/UA-1 can be declared in the same file. Semantic quality (list labels, table header scope) still needs a manual Matterhorn review. |

Constraints that follow from it:

- The worker must never read the clock or random sources for anything that ends up in the PDF; all values come from the job (release timestamp, ids).
- The **container image digest is part of the rendition's identity**: a byte-identical re-render is only guaranteed with the same image (WeasyPrint, Pango/HarfBuzz, fonts, templates). Record the image digest and template version with the checksum.
- Determinism is a property of our worker, not something WeasyPrint guarantees; it needs a regression test (golden checksums) on every image or template change.

## Follow-ups

- Production worker per [`spikes/rendering/README.md`](../../spikes/rendering/README.md) ("What the production worker needs"): job contract with release timestamp and ids, result object + SHA-256 + veraPDF report, golden-checksum test in CI.
- Debian packages are pinned by version only; build the image from `snapshot.debian.org` (or keep released images by digest) so it can be rebuilt after Debian security updates replace the pinned versions.
- PDF/UA: manual review of the tagged output (Matterhorn protocol, e.g. with PAC) before policies for the public sector are rendered as PDF/A-2a/3a + PDF/UA-1.
- Upstream: ask WeasyPrint for merged custom XMP (instead of a second `rdf:RDF`) to drop the wrapper.
- Record the rendering version (image digest, WeasyPrint, fonts, template version) per PDF in the audit trail.
- Ruled out if accepted: LibreOffice and Chromium in the archive path.

## Reversibility

**low**: The renderer is encapsulated behind the worker job; the input is HTML. A switch to Chromium affects only the worker; a switch to Typst requires a new generator from the content model. PDFs that are already archived remain unaffected.
