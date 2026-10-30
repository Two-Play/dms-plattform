# Rendering worker

HTML → PDF/A with WeasyPrint behind a small HTTP API (DMS-44, [ADR 0008](../../docs/adr/0008-rendering-weasyprint.md), [ADR 0025](../../docs/adr/0025-archive-renditions.md)).
The worker is stateless: no database, no object storage, no credentials, no outbound requests. The application's rendering job
(`dms.rendering.core.RenditionJobs`) builds the complete page, calls the worker, validates the PDF with veraPDF, stores it and
completes the approval.

## Run

```bash
docker compose up -d rendering        # http://localhost:9180, see compose.yaml
curl -s localhost:9180/health         # {"status": "ok", "renderer": "WeasyPrint 70.0; pydyf 0.13.0; worker 1"}
```

The application finds it at `dms.rendering.url` (`DMS_RENDERING_URL`, default `http://localhost:9180`). Tests build the image from
this directory with Testcontainers (no compose needed).

## API

`POST /render` with a JSON body:

| Field | Meaning |
|---|---|
| `html` | The complete page: content, stylesheet, `@page` rules with header/footer, `<title>` and `<meta>` tags (`dcterms.created`/`modified` set the PDF dates). Must not reference external resources. |
| `pdfVariant` | `pdf/a-2b` (default) or `pdf/a-3b` |
| `identifier` | PDF file identifier, 32 lowercase hex characters (stable per version, for reproducible bytes) |
| `xmp` | Custom XMP properties `documentId`, `version`, `validFrom` (strings), declared in a PDF/A extension schema |

Responses: `200 application/pdf` with headers `X-Renderer` (WeasyPrint, pydyf and worker version), `X-Pages`, `X-Worker-Image`
(the image reference from `DMS_WORKER_IMAGE`, `unknown` if unset) and `X-Worker-Build` (build fingerprint, see below); `400` malformed
request; `422` the page cannot be rendered (e.g. an external reference); `500` unexpected failure. `GET /health` answers `200`.

One render runs at a time per container (WeasyPrint is not documented as thread-safe); scale with replicas.

## Image identity (DMS-93, ADR 0055)

The application records with every rendition which worker produced it (`dms.rendition_provenance`, evidence report):

- `X-Worker-Build`: `sha256:` over the installed Debian packages and Python distributions (name and version), the Python
  version and `server.py`, computed once at start. Equal images give equal fingerprints, so a rendition can be traced to
  the build even where no registry digest exists (local builds).
- `X-Worker-Image`: the image reference with digest (`registry/dms-rendering-worker:<version>@sha256:<digest>`), passed in
  by the deployment as `DMS_WORKER_IMAGE`; a container cannot read its own image digest. In `compose.yaml` the variable
  `DMS_RENDERING_IMAGE` sets both the image compose runs and this reference; it defaults to the local build tag.

## Pinning

Everything that influences the PDF bytes is pinned in the `Dockerfile` and `requirements.txt` exactly as in spike DMS-23
(`spikes/rendering`): base image by digest, Pango/HarfBuzz/FreeType/fontconfig and Noto fonts by Debian version, Python packages by
version and hash. Bump `WORKER_VERSION` in `server.py` when the output changes.
