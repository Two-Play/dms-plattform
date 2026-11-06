#!/usr/bin/env python3
"""Whether two rendering workers print the same bytes (DMS-171, ADR 0081).

A change of how the worker runs, such as the child process per render, must not change what it prints. This sends the
same requests to both workers and compares the PDFs byte by byte, as PDF/A-2b and as a plain PDF, each request twice.

    git worktree add /tmp/main origin/main
    docker build -t rendering-old:local /tmp/main/workers/rendering
    docker build -t rendering-new:local workers/rendering
    docker run -d --rm --name rendering-old --read-only --tmpfs /tmp --memory 512m -p 127.0.0.1:19381:8080 rendering-old:local
    docker run -d --rm --name rendering-new --read-only --tmpfs /tmp --memory 512m -p 127.0.0.1:19382:8080 rendering-new:local
    python3 scripts/loadtest/render_compare.py http://127.0.0.1:19381 http://127.0.0.1:19382
    docker rm -f rendering-old rendering-new

Exit code 0 if all bytes are equal. The X-Renderer headers are compared as well: they are part of the XMP packet.
"""

import hashlib
import json
import sys
import urllib.request

from render_bench import SAMPLES, request_body, table


def render(url: str, body: bytes) -> tuple[str, str, int]:
    request = urllib.request.Request(f"{url}/render", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=600) as response:
        pdf = response.read()
    return hashlib.sha256(pdf).hexdigest(), response.headers["X-Renderer"], len(pdf)


def bodies() -> dict[str, bytes]:
    contract = (SAMPLES / "contract" / "content.html").read_text()
    policy = (SAMPLES / "policy" / "content.html").read_text()
    form = (SAMPLES / "form" / "content.html").read_text()
    archive = {
        "contract": request_body(contract),
        "contract x5": request_body("\n".join([contract] * 5)),
        "policy": request_body(policy),
        "form": request_body(form),
        "table": request_body(table(400)),
    }
    plain = {
        f"{name}, plain": json.dumps({**json.loads(body), "pdfVariant": "plain"}).encode()
        for name, body in archive.items()
    }
    return archive | plain


def main() -> int:
    old, new = sys.argv[1:3]
    equal = True
    for name, body in bodies().items():
        before = [render(old, body) for _ in range(2)]
        after = [render(new, body) for _ in range(2)]
        same = len(set(before + after)) == 1
        equal = equal and same
        verdict = "same" if same else "DIFFERENT"
        print(f"{verdict}  {name}: {before[0][2]} bytes, sha256 {before[0][0][:16]}, new {after[0][0][:16]}")
    print("all bytes equal" if equal else "the workers print different bytes")
    return 0 if equal else 1


if __name__ == "__main__":
    sys.exit(main())
