#!/usr/bin/env bash
# Benchmark one build of the app against the running Compose datastores.
#
#   benchmarks/run.sh <path-to-jar> <label> [k6-script]
#
# Starts the jar, creates a fresh short code, warms it (JIT + cache), runs k6, stops
# the app. Results land in benchmarks/results/<label>.{json,txt}.
#
# Both builds are run through this same script so the only difference between two
# results is the code under test.
set -euo pipefail

JAR="${1:?usage: run.sh <jar> <label> [k6-script]}"
LABEL="${2:?usage: run.sh <jar> <label> [k6-script]}"
K6_SCRIPT="${3:-redirect.js}"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="$REPO/benchmarks/results"
mkdir -p "$RESULTS"
chmod 777 "$RESULTS"   # the k6 image runs as uid 12345 and has to write its summary here

VUS="${VUS:-50}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-20s}"

# Datastore credentials, from the same .env the app uses locally.
set -a; . "$REPO/.env"; set +a
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6380}"   # Compose publishes 6379 as 6380

echo "==> starting $LABEL ($JAR)"
# Logging is pinned identically for both builds: the pre-change build issues SQL on
# every redirect, so leaving show-sql on would credit the change with console I/O it
# did not earn.
java -jar "$JAR" \
  --spring.jpa.show-sql=false \
  --logging.level.root=WARN \
  --logging.level.com.example.urlshortener=WARN \
  > "$RESULTS/$LABEL.app.log" 2>&1 &
APP_PID=$!
trap 'kill -TERM "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true' EXIT

# There is no actuator here, so "ready" means the port answers at all — a 404 from the
# app is as good a signal as a 200, and curl reports 000 while nothing is listening.
for _ in $(seq 1 120); do
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:8080/readiness" || true)
  [ "$status" != "000" ] && break
  kill -0 "$APP_PID" 2>/dev/null || { echo "app died, see $RESULTS/$LABEL.app.log"; exit 1; }
  sleep 1
done
[ "${status:-000}" != "000" ] || { echo "app never came up"; exit 1; }

CODE=""
if [ "$K6_SCRIPT" = "redirect.js" ]; then
  CODE="bench-$LABEL-$(date +%s)"
  curl -fsS -X POST http://localhost:8080/api/urls \
    -H 'Content-Type: application/json' \
    -d "{\"originalUrl\":\"https://example.com/benchmark-target\",\"customAlias\":\"$CODE\"}" \
    -o /dev/null
  echo "==> short code: $CODE"

  # Warmed, not measured: the first few thousand requests are the JIT compiling the
  # request path and the first one is a cache miss. Neither is what this measures.
  echo "==> warmup ${WARMUP} (JIT + cache fill, discarded)"
  docker run --rm --network host -v "$REPO/benchmarks:/script" \
    -e SHORT_CODE="$CODE" -e VUS="$VUS" -e DURATION="$WARMUP" \
    grafana/k6 run --quiet /script/redirect.js > /dev/null
fi

echo "==> measuring: $K6_SCRIPT ($VUS VUs)"
docker run --rm --network host -v "$RESULTS:/out" -v "$REPO/benchmarks:/script" \
  -e SHORT_CODE="$CODE" -e VUS="$VUS" -e DURATION="$DURATION" -e OUT="$LABEL" \
  -e ITERATIONS="${ITERATIONS:-1000}" -e GROUP="${GROUP:-25}" -e RUN="$LABEL-$(date +%s)" \
  grafana/k6 run --quiet "/script/$K6_SCRIPT"

# k6 runs as uid 12345, so the summaries land owned by a user that doesn't exist here.
# Rewriting them through cp hands them back to whoever ran this script.
for f in "$RESULTS/$LABEL.json" "$RESULTS/$LABEL.txt"; do
  [ -f "$f" ] && cp "$f" "$f.owned" && mv -f "$f.owned" "$f"
done

echo "==> stopping $LABEL"
