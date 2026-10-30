"""DMS conversion worker (DMS-115, ADR 0042): office files -> PDF/A-2b and HTML -> DOCX with LibreOffice over HTTP.

A stateless conversion service. The application sends the file as request body and gets the converted bytes back.
Everything with business meaning (checksums, validation, storage, status changes) stays in the application, which
validates every PDF/A with veraPDF before it archives it.

Endpoints:
  GET  /health                          200 {"status": "ok", "converter": "..."}
  POST /convert?target=...&name=...     body: the file; target "pdf/a-2b" or "docx"; name: the original file name
                                        (its extension selects the import filter)
                                        -> 200 with the converted bytes, headers X-Converter (LibreOffice and
                                        worker version), X-Worker-Image and X-Worker-Build (see the rendering
                                        worker); 400 malformed request, 413 too large, 415 unsupported type,
                                        422 the file cannot be converted, 500 unexpected failure

Isolation: every conversion runs in its own temporary directory with its own LibreOffice profile, which is deleted
afterwards; one conversion at a time per container (scale with replicas). LibreOffice gets no network access from
this process (it is started with a fixed command line, never with user-provided options), and the container should
run without outbound network (see compose.yaml).
"""

import argparse
import hashlib
import importlib.metadata
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

# Bump when this file changes in a way that affects the output.
WORKER_VERSION = "1"


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


# Image reference of this worker with its digest (e.g. registry/dms-conversion-worker:1@sha256:...), set by the
# deployment; the application records it with every rendition. A container cannot read its own image digest.
WORKER_IMAGE = os.environ.get("DMS_WORKER_IMAGE", "").strip() or "unknown"
WORKER_BUILD = "unknown"  # computed in main()

MAX_REQUEST_BYTES = int(os.environ.get("DMS_CONVERSION_MAX_BYTES", str(64 * 1024 * 1024)))
TIMEOUT_SECONDS = int(os.environ.get("DMS_CONVERSION_TIMEOUT_SECONDS", "110"))

# PDF/A-2b: SelectPdfVersion 2 of LibreOffice's PDF export.
PDFA2B = json.dumps({"SelectPdfVersion": {"type": "long", "value": "2"}}, separators=(",", ":"))

# Import extension -> LibreOffice PDF export filter of the application that opens it.
PDF_EXPORT = {
    **{e: "writer_pdf_Export" for e in ["doc", "docx", "docm", "dot", "dotx", "dotm", "odt", "ott", "rtf", "txt"]},
    **{e: "writer_web_pdf_Export" for e in ["html", "htm"]},
    **{e: "calc_pdf_Export" for e in ["xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "ots", "csv"]},
    **{e: "impress_pdf_Export" for e in ["ppt", "pptx", "pps", "ppsx", "pot", "potx", "odp", "otp"]},
    **{e: "draw_pdf_Export" for e in ["odg", "otg", "vsd", "vsdx", "pdf"]},
}
DOCX_SOURCES = {"html", "htm", "odt", "rtf", "doc"}
TARGETS = {"pdf/a-2b", "docx"}
EXTENSION = re.compile(r"^[a-z0-9]{1,10}$")


class BadRequest(Exception):
    """The request is malformed (400)."""


class Unsupported(Exception):
    """The file type cannot be converted into the target (415)."""


class Unconvertible(Exception):
    """LibreOffice could not convert the file (422)."""


def soffice_version() -> str:
    try:
        out = subprocess.run(["soffice", "--version"], capture_output=True, text=True, timeout=60, check=False)
        return out.stdout.strip().splitlines()[0] if out.stdout.strip() else "LibreOffice (unknown version)"
    except (OSError, subprocess.SubprocessError) as e:
        logging.warning("cannot read the LibreOffice version: %s", e)
        return "LibreOffice (unknown version)"


CONVERTER = ""


def parse_query(path: str) -> tuple[str, str]:
    query = parse_qs(urlparse(path).query)
    target = (query.get("target") or [""])[0]
    name = (query.get("name") or [""])[0]
    if target not in TARGETS:
        raise BadRequest(f"target must be one of {sorted(TARGETS)}")
    extension = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if not EXTENSION.match(extension):
        raise BadRequest("name must end with a file name extension")
    if target == "pdf/a-2b" and extension not in PDF_EXPORT:
        raise Unsupported(f"files of type .{extension} cannot be converted into PDF/A")
    if target == "docx" and extension not in DOCX_SOURCES:
        raise Unsupported(f"files of type .{extension} cannot be converted into DOCX")
    return target, extension


def command(target: str, extension: str, source: Path, out: Path, profile: Path) -> list[str]:
    base = [
        "soffice",
        f"-env:UserInstallation={profile.as_uri()}",
        "--headless",
        "--invisible",
        "--nologo",
        "--nodefault",
        "--norestore",
        "--nolockcheck",
    ]
    if target == "docx":
        # Open HTML in Writer (not Writer/Web), so that it can be saved as Word document.
        infilter = ["--infilter=HTML (StarWriter)"] if extension in ("html", "htm") else []
        convert = ["--convert-to", "docx:MS Word 2007 XML"]
    else:
        infilter = ["--infilter=HTML (StarWriter)"] if extension in ("html", "htm") else []
        export = "writer_pdf_Export" if extension in ("html", "htm") else PDF_EXPORT[extension]
        convert = ["--convert-to", f"pdf:{export}:{PDFA2B}"]
    return base + infilter + convert + ["--outdir", str(out), str(source)]


# One LibreOffice process at a time: conversions are CPU and memory heavy; scale with more containers.
CONVERT_LOCK = threading.Lock()


def convert(target: str, extension: str, data: bytes) -> bytes:
    work = Path(tempfile.mkdtemp(prefix="dms-conversion-"))
    try:
        source = work / f"input.{extension}"
        source.write_bytes(data)
        out = work / "out"
        out.mkdir()
        profile = work / "profile"
        with CONVERT_LOCK:
            try:
                result = subprocess.run(
                    command(target, extension, source, out, profile),
                    capture_output=True,
                    text=True,
                    timeout=TIMEOUT_SECONDS,
                    check=False,
                    cwd=work,
                )
            except subprocess.TimeoutExpired as e:
                raise Unconvertible(f"the conversion took longer than {TIMEOUT_SECONDS} seconds") from e
        produced = list(out.iterdir())
        if result.returncode != 0 or len(produced) != 1 or produced[0].stat().st_size == 0:
            detail = (result.stderr or result.stdout or "").strip().splitlines()[-1:] or ["no output"]
            raise Unconvertible(f"LibreOffice could not convert the file ({result.returncode}): {detail[0][:300]}")
        return produced[0].read_bytes()
    finally:
        shutil.rmtree(work, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "dms-conversion-worker/" + WORKER_VERSION
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if urlparse(self.path).path == "/health":
            self.reply_json(
                HTTPStatus.OK,
                {"status": "ok", "converter": CONVERTER, "image": WORKER_IMAGE, "build": WORKER_BUILD},
            )
        else:
            self.reply_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self):
        if urlparse(self.path).path != "/convert":
            self.reply_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_REQUEST_BYTES:
                self.reply_json(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": f"the body is larger than {MAX_REQUEST_BYTES} bytes"}
                )
                self.close_connection = True
                return
            if length <= 0:
                raise BadRequest("the body is empty")
            data = self.rfile.read(length)
            target, extension = parse_query(self.path)
            converted = convert(target, extension, data)
        except BadRequest as e:
            self.reply_json(HTTPStatus.BAD_REQUEST, {"error": str(e)})
        except Unsupported as e:
            self.reply_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": str(e)})
        except Unconvertible as e:
            self.reply_json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(e)})
        except Exception as e:  # noqa: BLE001 - report every other failure as 500 with its type
            logging.exception("conversion failed")
            self.reply_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"{type(e).__name__}: {e}"})
        else:
            media = (
                "application/pdf"
                if target == "pdf/a-2b"
                else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", media)
            self.send_header("Content-Length", str(len(converted)))
            self.send_header("X-Converter", CONVERTER)
            self.send_header("X-Worker-Image", WORKER_IMAGE)
            self.send_header("X-Worker-Build", WORKER_BUILD)
            self.end_headers()
            self.wfile.write(converted)

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
    global CONVERTER, WORKER_BUILD
    parser = argparse.ArgumentParser(description="DMS conversion worker")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    CONVERTER = f"{soffice_version()}; worker {WORKER_VERSION}"
    WORKER_BUILD = build_fingerprint()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    logging.info(
        "conversion worker listening on %s:%d (%s; image %s; build %s)",
        args.host, args.port, CONVERTER, WORKER_IMAGE, WORKER_BUILD,
    )
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
