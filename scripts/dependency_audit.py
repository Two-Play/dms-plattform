#!/usr/bin/env python3
"""Known vulnerabilities in the JVM dependencies of the application (DMS-60).

Asks the public OSV database (https://osv.dev) about every library on the runtime classpath. Only the names and
versions of open-source libraries leave the machine. For the frontend, use `npm audit` in frontend/.

    sbt -batch "export app/Runtime/fullClasspath" | tail -1 > classpath.txt
    scripts/dependency_audit.py classpath.txt

Standard library only. Exit code 1 if a library has a known vulnerability.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev/v1"
# Coursier's cache path: .../maven2/<group as a path>/<artifact>/<version>/<file>.jar
COORDINATES = re.compile(r"/maven2/(?P<group>.+)/(?P<artifact>[^/]+)/(?P<version>[^/]+)/[^/]+\.jar$")


def coordinates(classpath: str) -> list[tuple[str, str]]:
    found = set()
    for entry in classpath.strip().split(":"):
        match = COORDINATES.search(entry)
        if match:
            found.add((f"{match['group'].replace('/', '.')}:{match['artifact']}", match["version"]))
    return sorted(found)


def post(path: str, body: dict) -> dict:
    request = urllib.request.Request(f"{OSV}{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def vulnerabilities(libraries: list[tuple[str, str]]) -> dict[tuple[str, str], list[str]]:
    found: dict[tuple[str, str], list[str]] = {}
    for start in range(0, len(libraries), 500):
        chunk = libraries[start:start + 500]
        queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in chunk]
        for library, result in zip(chunk, post("/querybatch", {"queries": queries})["results"]):
            ids = [v["id"] for v in result.get("vulns") or []]
            if ids:
                found[library] = ids
    return found


def describe(vulnerability: str) -> str:
    with urllib.request.urlopen(f"{OSV}/vulns/{vulnerability}", timeout=60) as response:
        details = json.load(response)
    severity = details.get("database_specific", {}).get("severity", "unknown")
    return f"{vulnerability} [{severity}] {details.get('summary', '')[:100]}"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    libraries = coordinates(Path(sys.argv[1]).read_text())
    found = vulnerabilities(libraries)
    print(f"{len(libraries)} libraries checked, {len(found)} with known vulnerabilities")
    for (name, version), ids in sorted(found.items()):
        print(f"{name} {version}")
        for vulnerability in ids:
            print(f"  {describe(vulnerability)}")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
