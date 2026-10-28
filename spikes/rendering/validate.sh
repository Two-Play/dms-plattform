#!/usr/bin/env bash
# Spike DMS-23: validate rendered PDFs with the official veraPDF CLI image against the declared flavour.
# Expects ./determinism.sh deterministic to have run (uses out/deterministic/A/*-1.pdf).
# Also renders tagged variants (PDF/A-2a/3a, PDF/UA-1) and validates those.
set -euo pipefail
cd "$(dirname "$0")"

VERAPDF="verapdf/cli:v1.30.2@sha256:d5ee329657cf9bc4b2400392dd54c7d0a0ce9980ff6fa2da5590eebeec007cdb"
IMAGE="${IMAGE:-dms-render-spike:dms-23}"
SRC="out/deterministic/A"
REPORTS="results/verapdf"
mkdir -p "$REPORTS" out/tagged

verapdf() {
  docker run --rm --network none -v "$PWD/out:/data/out:ro" "$VERAPDF" "$@"
}

summary="results/verapdf-summary.txt"
: > "$summary"
for s in policy contract form; do
  for f in 2b 3b; do
    pdf="$SRC/$s-$f-1.pdf"
    # Machine-readable report (MRR) per file; plain text summary line for the README.
    verapdf --flavour "$f" --format mrr "/data/$pdf" > "$REPORTS/$s-$f.xml" || true
    verapdf --flavour "$f" --format text "/data/$pdf" | tee -a "$summary" || true
  done
done

# Accessibility check: tagged variants.
#   policy   pdf/ua-1 (WeasyPrint's own PDF/UA variant, not PDF/A)
#   policy   pdf/a-2a (tagged PDF/A)
#   contract pdf/a-3a + PDF/UA-1 identification (--pdfua)
#   form     pdf/a-2a + PDF/UA-1 identification (--pdfua)
render() {
  local sample="$1" name="$2" variant="$3"; shift 3
  docker run --rm --network none --read-only --tmpfs /tmp -e HOME=/tmp --user "$(id -u):$(id -g)" \
    -v "$PWD/samples:/work/samples:ro" -v "$PWD/out/tagged:/work/out" \
    "$IMAGE" "samples/$sample/job.json" "out/$name.pdf" --variant "$variant" "$@" > /dev/null
}
render policy policy-ua-1 pdf/ua-1
render policy policy-a-2a pdf/a-2a
render contract contract-a-3a-ua1 pdf/a-3a --pdfua
render form form-a-2a-ua1 pdf/a-2a --pdfua

check() {
  verapdf --flavour "$2" --format text "/data/out/tagged/$1.pdf" | tee -a "$summary" || true
  verapdf --flavour "$2" --format mrr "/data/out/tagged/$1.pdf" > "$REPORTS/$1-$2.xml" || true
}
check policy-ua-1 ua1
check policy-a-2a 2a
check contract-a-3a-ua1 3a
check contract-a-3a-ua1 ua1
check form-a-2a-ua1 2a
check form-a-2a-ua1 ua1
