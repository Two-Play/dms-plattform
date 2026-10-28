"""Spike DMS-23: show which objects differ between two PDFs written by pydyf.

Crude on purpose: splits the file into indirect objects, inflates Flate streams
and prints a short, normalised excerpt of every object (and the trailer) that differs.
Usage: python pdfdiff.py a.pdf b.pdf
"""

import re
import sys
import zlib

OBJ = re.compile(rb"(\d+) 0 obj\n(.*?)\nendobj", re.S)


def objects(path):
    data = open(path, "rb").read()
    result = {}
    for match in OBJ.finditer(data):
        body = match.group(2)
        if b"stream\n" in body:
            head, _, rest = body.partition(b"stream\n")
            raw = rest.rsplit(b"\nendstream", 1)[0]
            if b"/FlateDecode" in head:
                try:
                    raw = zlib.decompress(raw)
                except zlib.error:
                    pass
            body = head + b"stream:" + raw
        result[int(match.group(1))] = body
    trailer = data[data.rfind(b"trailer"):] if b"trailer" in data else data[-400:]
    result["trailer"] = trailer
    return result


def excerpt(a, b, width=110):
    # Show the region around the first differing byte.
    i = next((k for k, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
    start = max(0, i - 40)
    return a[start:start + width], b[start:start + width]


def main():
    left, right = objects(sys.argv[1]), objects(sys.argv[2])
    differing = [k for k in left if left[k] != right.get(k)]
    print(f"objects: {len(left)} vs {len(right)}, differing: {len(differing)}")
    for key in differing:
        a, b = excerpt(left[key], right.get(key, b""))
        print(f"--- object {key}")
        print(f"  A: {a!r}")
        print(f"  B: {b!r}")


if __name__ == "__main__":
    main()
