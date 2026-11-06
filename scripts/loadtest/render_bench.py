#!/usr/bin/env python3
"""How long the rendering worker takes for a rendition (DMS-137, ADR 0079; business concept: up to 50 pages, p95 <= 10 s).

Talks to a worker directly, so it needs nothing but a running container of `workers/rendering`. Start your own and
leave the shared stack alone:

    docker build -t rendering-bench:local workers/rendering
    docker run -d --rm --name rendering-bench --read-only --tmpfs /tmp --memory 512m \
      -p 127.0.0.1:19380:8080 rendering-bench:local
    python3 scripts/loadtest/render_bench.py --url http://127.0.0.1:19380 --runs 30
    python3 scripts/loadtest/render_bench.py --url http://127.0.0.1:19380 --document contract --pages 50 \
      --runs 24 --parallel 4
    docker rm -f rendering-bench

Pages are composed like `RenditionLayout.compose` does for an archive rendition: the document stylesheet of the
templates module, running header and footer on every page, PDF/A-2b with the XMP packet. The content comes from the
samples of spike DMS-23:

    contract   the 12-page service agreement (numbered clauses, tables, three annexes), repeated up to the page count
    policy     the 2-page policy, repeated likewise
    table      one single table across all pages, built from the cells of the contract

With --out the composed requests and the PDFs are written to a directory, for a validator.
"""

import argparse
import html
import json
import math
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STYLESHEET = ROOT / "modules/templates/core/src/main/resources/dms/templates/document.css"
SAMPLES = ROOT / "spikes/rendering/samples"

MARGIN = 'font-family: "Noto Sans", sans-serif; font-size: 7.5pt; color: #333; white-space: pre-wrap;'
PAGE_RULES = f"""@page {{
  @top-left {{ content: "IT Service Agreement"; width: 55%; vertical-align: bottom; padding-bottom: 3mm;
    border-bottom: 0.5pt solid #888; {MARGIN} }}
  @top-right {{ content: "Document DOC-2026-0318 · Version 2.0"; width: 45%; text-align: right; vertical-align: bottom;
    padding-bottom: 3mm; border-bottom: 0.5pt solid #888; {MARGIN} }}
  @bottom-left {{ content: "Valid from 01.01.2027\\A Approved 24.10.2026 09:17 UTC by Approver"; width: 75%;
    vertical-align: top; padding-top: 3mm; border-top: 0.5pt solid #888; {MARGIN} }}
  @bottom-right {{ content: "Page " counter(page) " of " counter(pages); width: 25%; text-align: right;
    vertical-align: top; padding-top: 3mm; border-top: 0.5pt solid #888; {MARGIN} }}
}}
"""
META = """<meta name="keywords" content="DOC-2026-0318">
<meta name="keywords" content="version 2.0">
<meta name="generator" content="DMS rendition-layout-2">
<meta name="dcterms.created" content="2026-10-24T09:17:00Z">
<meta name="dcterms.modified" content="2026-10-24T09:17:00Z">
"""


def page(content: str) -> str:
    return (
        '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<title>IT Service Agreement</title>\n'
        f"<style>\n{STYLESHEET.read_text()}</style>\n{META}<style>\n{PAGE_RULES}</style>\n</head>\n<body>\n"
        f'<article class="dms-document">\n{content}\n</article>\n</body>\n</html>\n'
    )


def table(rows: int) -> str:
    contract = (SAMPLES / "contract/content.html").read_text()
    cells = [html.unescape(re.sub(r"<[^>]+>", "", c)) for c in re.findall(r"<td[^>]*>(.*?)</td>", contract, re.S)]
    cells = [c for c in cells if c.strip()]
    body = "".join(
        "<tr>" + "".join(f"<td>{html.escape(cells[(r * 4 + c) % len(cells)])}</td>" for c in range(4)) + "</tr>\n"
        for r in range(rows)
    )
    return (
        "<h1>Service catalogue</h1>\n<table><thead><tr><th>Item</th><th>Target</th><th>Measure</th><th>Note</th></tr>"
        f"</thead><tbody>\n{body}</tbody></table>"
    )


def request_body(content: str) -> bytes:
    return json.dumps(
        {
            "html": page(content),
            "pdfVariant": "pdf/a-2b",
            "identifier": "0" * 32,
            "xmp": {"documentId": "DOC-2026-0318", "version": "2.0", "validFrom": "2027-01-01"},
        }
    ).encode()


def render(url: str, body: bytes) -> tuple[float, int, bytes]:
    request = urllib.request.Request(f"{url}/render", body, {"Content-Type": "application/json"})
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=600) as response:
        pdf = response.read()
        return time.perf_counter() - start, int(response.headers["X-Pages"]), pdf


def sized(url: str, document: str, pages: int) -> tuple[bytes, int]:
    """The request with at least `pages` pages and not many more, found by rendering."""

    def content(units: int) -> str:
        if document == "table":
            return table(units)
        return "\n".join([(SAMPLES / document / "content.html").read_text()] * units)

    units = 40 if document == "table" else 1
    _, got, _ = render(url, request_body(content(units)))
    units = max(units, int(units * pages / got))
    while True:
        body = request_body(content(units))
        _, got, _ = render(url, body)
        if got >= pages:
            return body, got
        units += max(1, units // 50)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(p / 100 * len(ordered)) - 1)]


def measure(urls: list[str], document: str, pages: int, runs: int, parallel: int, out: Path | None) -> dict:
    url = urls[0]
    body, got = sized(url, document, pages)
    for each in urls:
        render(each, body)  # warm-up, not counted
    times: list[float] = []
    lock = threading.Lock()
    remaining = [runs]

    def client(target: str) -> None:
        while True:
            with lock:
                if remaining[0] == 0:
                    return
                remaining[0] -= 1
            elapsed, _, _ = render(target, body)
            with lock:
                times.append(elapsed)

    started = time.perf_counter()
    clients = [threading.Thread(target=client, args=(urls[i % len(urls)],)) for i in range(parallel)]
    for c in clients:
        c.start()
    for c in clients:
        c.join()
    total = time.perf_counter() - started
    if out:
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{document}-{got}.json").write_bytes(body)
        (out / f"{document}-{got}.pdf").write_bytes(render(url, body)[2])
    return {
        "document": document,
        "pages": got,
        "runs": runs,
        "parallel": parallel,
        "workers": len(urls),
        "min": round(min(times), 2),
        "p50": round(statistics.median(times), 2),
        "p95": round(percentile(times, 95), 2),
        "max": round(max(times), 2),
        "per_minute": round(runs / total * 60, 1),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", action="append", help="a worker; several: clients are spread over them")
    parser.add_argument("--document", choices=["contract", "policy", "table"], action="append")
    parser.add_argument("--pages", type=int, action="append", help="default 12, 25 and 50")
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--parallel", type=int, default=1, help="requests at the same time")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    urls = args.url or ["http://127.0.0.1:19380"]
    for url in urls:
        try:
            urllib.request.urlopen(f"{url}/health", timeout=5).read()
        except urllib.error.URLError as e:
            print(f"no worker at {url}: {e}", file=sys.stderr)
            return 1
    for document in args.document or ["contract", "policy", "table"]:
        for pages in args.pages or [12, 25, 50]:
            print(json.dumps(measure(urls, document, pages, args.runs, args.parallel, args.out)), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
