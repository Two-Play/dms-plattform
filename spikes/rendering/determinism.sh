#!/usr/bin/env bash
# Spike DMS-23: determinism test.
# Renders every sample in PDF/A-2b and PDF/A-3b RUNS times in one container and RUNS times in a
# second, fresh container, then compares the SHA-256 of all outputs per sample and variant.
#
# Usage: ./determinism.sh [default|naive|deterministic] [RUNS]
# B_ARGS: extra docker run arguments for the second container only (e.g. a different TZ or hash seed).
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-deterministic}"
RUNS="${2:-10}"
IMAGE="${IMAGE:-dms-render-spike:dms-23}"
SAMPLES=(policy contract form)
VARIANTS=(pdf/a-2b pdf/a-3b)
OUT="out/$MODE"
RESULTS="results"

rm -rf "$OUT"
mkdir -p "$OUT/A" "$OUT/B" "$RESULTS"

# In naive mode wait between runs, as real re-renders happen at different times.
PAUSE=0
[[ "$MODE" != "deterministic" ]] && PAUSE=1.1

run_container() {
  local name="$1"; shift
  # Fresh container per call: --rm, no network, read-only root filesystem, output via bind mount.
  docker run --rm --network none --read-only --tmpfs /tmp \
    --user "$(id -u):$(id -g)" \
    -v "$PWD/samples:/work/samples:ro" -v "$PWD/$OUT/$name:/work/out" \
    -e HOME=/tmp -e MODE="$MODE" -e RUNS="$RUNS" -e PAUSE="$PAUSE" -e SAMPLES="${SAMPLES[*]}" -e VARIANTS="${VARIANTS[*]}" \
    "$@" --entrypoint bash "$IMAGE" -c '
      for i in $(seq 1 "$RUNS"); do
        for s in $SAMPLES; do
          for v in $VARIANTS; do
            python /worker/render.py "samples/$s/job.json" "out/$s-${v#pdf/a-}-$i.pdf" --mode "$MODE" --variant "$v"
          done
        done
        sleep "$PAUSE"
      done' > "$OUT/$name/results.jsonl"
}

echo "== mode=$MODE runs=$RUNS image=$IMAGE ($(docker image inspect -f '{{.Id}}' "$IMAGE"))"
run_container A
# shellcheck disable=SC2086
run_container B ${B_ARGS:-}

report="$RESULTS/determinism-$MODE${SUFFIX:-}.txt"
{
  echo "mode=$MODE runs_per_container=$RUNS containers=2 image=$(docker image inspect -f '{{.Id}}' "$IMAGE") B_ARGS=${B_ARGS:-}"
  printf '%-10s %-6s %8s %14s  %s\n' sample flavor renders distinct_sha256 sha256
  for s in "${SAMPLES[@]}"; do
    for v in "${VARIANTS[@]}"; do
      f="${v#pdf/a-}"
      hashes=$(sha256sum "$OUT"/A/"$s-$f"-*.pdf "$OUT"/B/"$s-$f"-*.pdf | cut -d' ' -f1 | sort -u)
      n=$(ls "$OUT"/A/"$s-$f"-*.pdf "$OUT"/B/"$s-$f"-*.pdf | wc -l)
      printf '%-10s %-6s %8s %14s  %s\n' "$s" "$f" "$n" "$(echo "$hashes" | wc -l)" "$(echo "$hashes" | head -1)"
    done
  done
} | tee "$report"
cat "$OUT"/A/results.jsonl "$OUT"/B/results.jsonl > "$RESULTS/timings-$MODE${SUFFIX:-}.jsonl"
