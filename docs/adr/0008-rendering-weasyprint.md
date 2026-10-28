# 0008: Rendering HTML → PDF/A with WeasyPrint in the Python worker, validation with veraPDF

| Field | Value |
|---|---|
| Status | Proposed (determinism spike) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Approved documents are archived as PDF/A (audit-proof, 0009). The structured content model (0007) is transformed into HTML and must be printed with corporate design, headers/footers, page numbers and a table of contents. The same input document must produce a byte-identical or at least visually identical PDF. Accessibility (PDF/UA) is relevant for policies in the public sector.

## Decision

Proposed: rendering HTML → PDF/A with WeasyPrint in a separate Python worker that fetches jobs from the job table (0010). Every generated PDF is validated with veraPDF; only valid PDFs are archived. Final acceptance after a spike on determinism.

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

## Follow-ups

- Determinism spike: render the same input document twice, compare bytes after normalizing the creation date and document ID; embed fonts; pin the WeasyPrint version.
- PDF/UA spike: check tagging quality with the veraPDF PDF/UA profile.
- Interface worker ↔ platform: job with HTML/assets from object storage, result as an object plus a validation report.
- Record the rendering version (WeasyPrint, fonts, CSS) per PDF in the audit trail.
- Ruled out if accepted: LibreOffice and Chromium in the archive path.

## Reversibility

**low**: The renderer is encapsulated behind the worker job; the input is HTML. A switch to Chromium affects only the worker; a switch to Typst requires a new generator from the content model. PDFs that are already archived remain unaffected.
