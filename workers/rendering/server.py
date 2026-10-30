"""DMS rendering worker (DMS-44, ADR 0008 addendum, ADR 0025): HTML -> PDF/A with WeasyPrint over HTTP.

A stateless print service. The application builds the complete HTML page (content, stylesheet, running
header/footer, document metadata as <meta> tags), calls POST /render and gets the PDF bytes back. Everything
with business meaning (jobs, checksums, validation, storage, status changes) stays in the application.

Determinism (spike DMS-23): nothing that ends up in the PDF comes from the clock or from random sources.
Creation and modification dates come from the page's dcterms.created / dcterms.modified meta tags, the file
identifier from the request. The service never fetches anything over the network: any reference to an
external resource fails the render.

Endpoints:
  GET  /health   200 {"status": "ok", "renderer": "..."}
  POST /render   JSON request (see RenderRequest below) -> 200 application/pdf
                 headers X-Renderer (renderer versions), X-Pages (page count), X-Worker-Image (image
                 reference from DMS_WORKER_IMAGE) and X-Worker-Build (build fingerprint)
                 400 malformed request, 422 the page cannot be rendered as requested, 500 unexpected failure
"""

import argparse
import hashlib
import html
import importlib.metadata
import json
import logging
import os
import re
import subprocess
import sys
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from xml.etree import ElementTree

import pydyf
import weasyprint
from weasyprint.urls import FatalURLFetchingError, URLFetcher

# Bump when this file changes in a way that affects the PDF bytes.
WORKER_VERSION = "1"
RENDERER = f"WeasyPrint {weasyprint.__version__}; pydyf {pydyf.__version__}; worker {WORKER_VERSION}"


def build_fingerprint() -> str:
    """SHA-256 over what determines this worker's output (DMS-93, ADR 0055): the Debian packages and Python
    distributions installed in the image, the Python version and this file. Equal images give equal
    fingerprints, so a rendition can be traced to the build that produced it even without a registry digest."""
    digest = hashlib.sha256()
    try:
        packages = subprocess.run(
            ["dpkg-query", "-W", "-f", "${Package}=${Version}\n"],
            capture_output=True, text=True, timeout=30, check=True,
        ).stdout.splitlines()
    except (OSError, subprocess.SubprocessError) as e:
        logging.warning("cannot list the Debian packages: %s", e)
        return "unknown"
    distributions = [f"{d.metadata['Name']}=={d.version}" for d in importlib.metadata.distributions()]
    for line in sorted(packages) + sorted(distributions) + [sys.version]:
        digest.update(line.encode() + b"\n")
    digest.update(Path(__file__).read_bytes())
    return "sha256:" + digest.hexdigest()


# Image reference of this worker with its digest (e.g. registry/dms-rendering-worker:1@sha256:...), set by the
# deployment; the application records it with every rendition. A container cannot read its own image digest.
WORKER_IMAGE = os.environ.get("DMS_WORKER_IMAGE", "").strip() or "unknown"
WORKER_BUILD = "unknown"  # computed in main()

VARIANTS = {"pdf/a-2b", "pdf/a-3b"}
MAX_REQUEST_BYTES = 32 * 1024 * 1024
IDENTIFIER = re.compile(r"^[0-9a-f]{32}$")

DMS_NS = "https://dms.it-heimnet.de/ns/document/1.0/"
for _prefix, _uri in {
    "pdfaExtension": "http://www.aiim.org/pdfa/ns/extension/",
    "pdfaSchema": "http://www.aiim.org/pdfa/ns/schema#",
    "pdfaProperty": "http://www.aiim.org/pdfa/ns/property#",
    "dms": DMS_NS,
}.items():
    ElementTree.register_namespace(_prefix, _uri)

# Custom XMP properties. PDF/A requires every non-standard property to be declared in a pdfaExtension schema
# inside the same packet.
XMP_PROPERTIES = [
    ("documentId", "Text", "external", "DMS document id"),
    ("version", "Text", "external", "Approved document version"),
    ("validFrom", "Text", "external", "Date the version becomes valid (ISO 8601)"),
    ("renderer", "Text", "internal", "Renderer, worker and template versions"),
]
XMP_TEMPLATE = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about=""
   xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/"
   xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#"
   xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
  <pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType="Resource">
   <pdfaSchema:schema>DMS document identification</pdfaSchema:schema>
   <pdfaSchema:namespaceURI>{ns}</pdfaSchema:namespaceURI>
   <pdfaSchema:prefix>dms</pdfaSchema:prefix>
   <pdfaSchema:property><rdf:Seq>{declarations}</rdf:Seq></pdfaSchema:property>
  </rdf:li></rdf:Bag></pdfaExtension:schemas>
 </rdf:Description>
 <rdf:Description rdf:about="" xmlns:dms="{ns}">{values}</rdf:Description>
</rdf:RDF>"""


class BadRequest(Exception):
    """The request is malformed (400)."""


class Unrenderable(Exception):
    """The page cannot be rendered as requested (422)."""


def xmp_packet(properties: dict) -> bytes:
    declarations = "".join(
        f'<rdf:li rdf:parseType="Resource"><pdfaProperty:name>{name}</pdfaProperty:name>'
        f"<pdfaProperty:valueType>{value_type}</pdfaProperty:valueType>"
        f"<pdfaProperty:category>{category}</pdfaProperty:category>"
        f"<pdfaProperty:description>{description}</pdfaProperty:description></rdf:li>"
        for name, value_type, category, description in XMP_PROPERTIES
    )
    values = "".join(
        f"<dms:{name}>{html.escape(str(properties[name]))}</dms:{name}>"
        for name, _, _, _ in XMP_PROPERTIES
        if properties.get(name)
    )
    return XMP_TEMPLATE.format(ns=DMS_NS, declarations=declarations, values=values).encode()


def merge_xmp(metadata, extra_rdf: bytes) -> None:
    """Puts our rdf:Description elements into WeasyPrint's single rdf:RDF element.

    WeasyPrint's xmp_metadata option appends a second rdf:RDF, which XMP does not allow and veraPDF ignores
    (spike DMS-23), so the metadata generator is wrapped instead.
    """
    original = metadata.generate_rdf_metadata

    def generate(variant, version, conformance):
        rdf = ElementTree.fromstring(original(variant, version, conformance))
        rdf.extend(list(ElementTree.fromstring(extra_rdf)))
        return ElementTree.tostring(rdf, encoding="utf-8")

    metadata.generate_rdf_metadata = generate


def parse_request(body: bytes) -> dict:
    try:
        request = json.loads(body)
    except (ValueError, UnicodeDecodeError) as e:
        raise BadRequest(f"the body is not JSON: {e}") from e
    if not isinstance(request, dict):
        raise BadRequest("the body must be a JSON object")
    page = request.get("html")
    if not isinstance(page, str) or not page.strip():
        raise BadRequest("html is required")
    variant = request.get("pdfVariant", "pdf/a-2b")
    if variant not in VARIANTS:
        raise BadRequest(f"pdfVariant must be one of {sorted(VARIANTS)}")
    identifier = request.get("identifier")
    if identifier is not None and (not isinstance(identifier, str) or not IDENTIFIER.match(identifier)):
        raise BadRequest("identifier must be 32 lowercase hex characters")
    properties = request.get("xmp", {})
    if not isinstance(properties, dict) or not all(isinstance(v, str) for v in properties.values()):
        raise BadRequest("xmp must be an object of strings")
    return {"html": page, "variant": variant, "identifier": identifier, "xmp": properties}


class NoFetching(URLFetcher):
    """No network and no file access while rendering: every resource must be inside the page. A reference to
    anything else fails the whole render instead of leaving a gap in the archive copy."""

    def __init__(self):
        super().__init__(fail_on_errors=True)

    def fetch(self, url, headers=None):
        raise Unrenderable(f"external resource not allowed: {url[:200]}")


# WeasyPrint and its native libraries are not documented as thread-safe: one render at a time per process.
# Scale with more containers.
RENDER_LOCK = threading.Lock()


def render(request: dict) -> tuple[bytes, int]:
    xmp = xmp_packet({**request["xmp"], "renderer": request["xmp"].get("renderer") or RENDERER})
    with RENDER_LOCK:
        try:
            document = weasyprint.HTML(string=request["html"], url_fetcher=NoFetching()).render()
        except FatalURLFetchingError as e:
            raise Unrenderable(str(e.__cause__ or e)) from e
        merge_xmp(document.metadata, xmp)
        identifier = request["identifier"].encode() if request["identifier"] else None
        pdf = document.write_pdf(pdf_variant=request["variant"], pdf_identifier=identifier)
    return pdf, len(document.pages)


class Handler(BaseHTTPRequestHandler):
    server_version = "dms-rendering-worker/" + WORKER_VERSION
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/health":
            self.reply_json(
                HTTPStatus.OK,
                {"status": "ok", "renderer": RENDERER, "image": WORKER_IMAGE, "build": WORKER_BUILD},
            )
        else:
            self.reply_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self):
        if self.path != "/render":
            self.reply_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_REQUEST_BYTES:
                raise BadRequest(f"Content-Length must be between 1 and {MAX_REQUEST_BYTES}")
            request = parse_request(self.rfile.read(length))
            pdf, pages = render(request)
        except BadRequest as e:
            self.reply_json(HTTPStatus.BAD_REQUEST, {"error": str(e)})
        except Unrenderable as e:
            self.reply_json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(e)})
        except Exception as e:  # noqa: BLE001 - report every other failure as 500 with its type
            logging.exception("render failed")
            self.reply_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"{type(e).__name__}: {e}"})
        else:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(pdf)))
            self.send_header("X-Renderer", RENDERER)
            self.send_header("X-Pages", str(pages))
            self.send_header("X-Worker-Image", WORKER_IMAGE)
            self.send_header("X-Worker-Build", WORKER_BUILD)
            self.end_headers()
            self.wfile.write(pdf)

    def reply_json(self, status: HTTPStatus, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        logging.info("%s %s", self.address_string(), format % args)


def main() -> int:
    parser = argparse.ArgumentParser(description="DMS rendering worker")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # WeasyPrint's progress messages are noise; its warnings (layout, CSS) explain unexpected output and stay.
    logging.getLogger("weasyprint.progress").setLevel(logging.WARNING)
    global WORKER_BUILD
    WORKER_BUILD = build_fingerprint()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    logging.info(
        "rendering worker listening on %s:%d (%s; image %s; build %s)",
        args.host, args.port, RENDERER, WORKER_IMAGE, WORKER_BUILD,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
