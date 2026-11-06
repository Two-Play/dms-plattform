"""DMS rendering worker (DMS-44, ADR 0008 addendum, ADR 0025): HTML -> PDF with WeasyPrint over HTTP.

A stateless print service. The application builds the complete HTML page (content, stylesheet, running
header/footer, document metadata as <meta> tags), calls POST /render and gets the PDF bytes back. Everything
with business meaning (jobs, checksums, validation, storage, status changes) stays in the application.

Determinism (spike DMS-23): nothing that ends up in the PDF comes from the clock or from random sources.
Creation and modification dates come from the page's dcterms.created / dcterms.modified meta tags, the file
identifier from the request. The service never fetches anything over the network: any reference to an
external resource fails the render.

Variants: PDF/A for everything archived, "plain" for drafts printed on demand (DMS-118, ADR 0073): an ordinary PDF
with no PDF/A identification, no output intent and no XMP packet, so that it cannot pass for an archive copy.

Limits (DMS-171, ADR 0081): a page estimated above DMS_RENDER_MAX_PAGES is refused before it is rendered. Every render
runs in a child process of its own that is killed after DMS_RENDER_TIME_LIMIT_SECONDS: WeasyPrint cannot be interrupted
in a thread. So neither a long document nor the memory it needs can take this process, or its lock, with it.

Endpoints:
  GET  /health   200 {"status": "ok", "renderer": "..."}
  POST /render   JSON request (see RenderRequest below) -> 200 application/pdf
                 headers X-Renderer (renderer versions), X-Pages (page count), X-Worker-Image (image
                 reference from DMS_WORKER_IMAGE) and X-Worker-Build (build fingerprint)
                 400 malformed request, 422 the page cannot be rendered as requested, 500 unexpected failure
                 errors are JSON with "error"; a 422 for a limit also has "code": "too_long" (over the page limit, or
                 the render ran out of memory) or "time_limit" (killed at the time limit), which a retry cannot cure
"""

import argparse
import hashlib
import html
import importlib.metadata
import json
import logging
import math
import multiprocessing
import multiprocessing.forkserver
import os
import re
import signal
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

PLAIN = "plain"
VARIANTS = {"pdf/a-2b", "pdf/a-3b", PLAIN}
MAX_REQUEST_BYTES = 32 * 1024 * 1024
IDENTIFIER = re.compile(r"^[0-9a-f]{32}$")


def limit_from_env(name: str, default: int) -> int:
    """A limit of the deployment. A value that is no positive number stops the worker at start instead of turning
    the limit off unnoticed."""
    value = os.environ.get(name, "").strip()
    limit = int(value) if value else default
    if limit <= 0:
        raise ValueError(f"{name} must be positive")
    return limit


# Keep the application's dms.rendering.timeoutSeconds above the time limit, or it gives up first and retries.
RENDER_TIME_LIMIT_SECONDS = limit_from_env("DMS_RENDER_TIME_LIMIT_SECONDS", 120)
MAX_ESTIMATED_PAGES = limit_from_env("DMS_RENDER_MAX_PAGES", 300)

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


class OverLimit(Unrenderable):
    """The page is beyond a limit of this worker (422 with a code). It would fail the same way on every attempt."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


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


# What a page of the document stylesheet holds, measured on the samples of spike DMS-23 (ADR 0081): running text fills
# a page at about 2,000 characters, clauses and tables at about 105 tags. Tags are also what memory and time grow with.
CHARS_PER_PAGE = 2000
TAGS_PER_PAGE = 105
TAG = re.compile(r"<[^<>]*>")  # linear also on malformed input; an image's data URI goes with its tag


def estimated_pages(page: str) -> int:
    """The pages to expect, from the body alone, without any layout. This runs in the server, so it stays linear."""
    body = page[max(page.find("<body"), 0):]
    text, tags = TAG.subn("", body)
    return math.ceil(max(len(text) / CHARS_PER_PAGE, tags / TAGS_PER_PAGE))


def check_length(page: str) -> None:
    estimate = estimated_pages(page)
    if estimate > MAX_ESTIMATED_PAGES:
        raise OverLimit("too_long", f"the document has about {estimate} pages, the limit is {MAX_ESTIMATED_PAGES}")


def render(request: dict) -> tuple[bytes, int]:
    plain = request["variant"] == PLAIN
    xmp = None if plain else xmp_packet({**request["xmp"], "renderer": request["xmp"].get("renderer") or RENDERER})
    try:
        document = weasyprint.HTML(string=request["html"], url_fetcher=NoFetching()).render()
    except FatalURLFetchingError as e:
        raise Unrenderable(str(e.__cause__ or e)) from e
    if xmp is not None:
        merge_xmp(document.metadata, xmp)
    identifier = request["identifier"].encode() if request["identifier"] else None
    pdf = document.write_pdf(pdf_variant=None if plain else request["variant"], pdf_identifier=identifier)
    return pdf, len(document.pages)


def configure_logging() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # WeasyPrint's progress messages are noise; its warnings (layout, CSS) explain unexpected output and stay.
    logging.getLogger("weasyprint.progress").setLevel(logging.WARNING)


def render_in_child(request: dict, result) -> None:
    """The child process of one render: sends back the outcome, or dies and says nothing."""
    configure_logging()
    try:
        outcome = ("rendered", *render(request))
    except Unrenderable as e:
        outcome = ("unrenderable", str(e))
    except Exception as e:  # noqa: BLE001 - every other failure is reported as 500 with its type
        logging.exception("render failed")
        outcome = ("failed", f"{type(e).__name__}: {e}")
    result.send(outcome)
    result.close()


# A single-threaded process that has imported this file forks one child per render: forking this threaded server
# would copy its locks, and a fresh interpreter per render would pay for the imports each time.
CHILDREN = multiprocessing.get_context("forkserver")

# WeasyPrint and its native libraries are not documented as thread-safe: one render at a time per process, and one
# child at a time per container, also because of the memory limit. Scale with more containers.
RENDER_LOCK = threading.Lock()


def render_limited(request: dict) -> tuple[bytes, int]:
    """Renders in a child process and ends it at the time limit. The lock is free again in every case."""
    with RENDER_LOCK:
        receiver, sender = CHILDREN.Pipe(duplex=False)
        child = CHILDREN.Process(target=render_in_child, args=(request, sender), daemon=True)
        child.start()
        sender.close()
        try:
            if not receiver.poll(RENDER_TIME_LIMIT_SECONDS):
                logging.warning("render stopped at the time limit of %d s", RENDER_TIME_LIMIT_SECONDS)
                raise OverLimit("time_limit", f"the render took longer than {RENDER_TIME_LIMIT_SECONDS} seconds")
            try:
                outcome = receiver.recv()
            except (EOFError, OSError):  # poll() also ends when the child dies
                outcome = None
                child.join(5)
        finally:
            child.kill()
            child.join()
            receiver.close()
    if outcome is None:
        # SIGKILL from anyone but us: the kernel, at the container's memory limit. One render at a time, so it was
        # this document that needed the memory.
        logging.warning("render child ended without a result, exit code %s", child.exitcode)
        if child.exitcode == -signal.SIGKILL:
            raise OverLimit("too_long", "the document needs more memory than the rendering worker has")
        raise RuntimeError(f"the render ended unexpectedly with exit code {child.exitcode}")
    match outcome:
        case ("rendered", pdf, pages):
            return pdf, pages
        case ("unrenderable", message):
            raise Unrenderable(message)
        case (_, message):
            raise RuntimeError(message)


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
            check_length(request["html"])
            pdf, pages = render_limited(request)
        except BadRequest as e:
            self.reply_json(HTTPStatus.BAD_REQUEST, {"error": str(e)})
        except OverLimit as e:
            self.reply_json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(e), "code": e.code})
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
    configure_logging()
    global WORKER_BUILD
    WORKER_BUILD = build_fingerprint()
    CHILDREN.set_forkserver_preload(["__main__"])
    multiprocessing.forkserver.ensure_running()  # the imports happen now, not in the first render
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    logging.info(
        "rendering worker listening on %s:%d (%s; image %s; build %s; time limit %d s; about %d pages at most)",
        args.host, args.port, RENDERER, WORKER_IMAGE, WORKER_BUILD, RENDER_TIME_LIMIT_SECONDS, MAX_ESTIMATED_PAGES,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
elif __name__ == "__mp_main__":
    # The fork server imports this file under that name. It loads the fonts once for all its children: on its own, every
    # render would spend more than a second on that.
    weasyprint.HTML(string="<p>fonts</p>", url_fetcher=NoFetching()).render()
