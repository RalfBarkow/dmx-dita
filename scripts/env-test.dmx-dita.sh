#!/usr/bin/env bash
set -euo pipefail

# Tiny DMX + dmx-dita environment test (CI-friendly).
# Assertions:
# 1) Java 8 and Node are present.
# 2) DMX responds on /dmx/version (or at least on a fallback health endpoint).
# 3) dmx-dita responds on a health endpoint.
# 4) A minimal DITA job produces at least one HTML file.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DMX_ROOT_DEFAULT="$(cd "${ROOT_DIR}/../.." && pwd)"

# Optional overrides live in config/env-test.dmx-dita.env (next to this script tree)
if [[ -f "${ROOT_DIR}/config/env-test.dmx-dita.env" ]]; then
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/config/env-test.dmx-dita.env"
fi

DMX_HOST="${DMX_HOST:-127.0.0.1}"
DMX_PORT="${DMX_PORT:-8080}"
DMX_BASE_URL="http://${DMX_HOST}:${DMX_PORT}"

# Preferred health endpoint (future: /dmx/version).
DMX_HEALTH_URL="${DMX_HEALTH_URL:-${DMX_BASE_URL}/dmx/version}"

# Fallback: something that exists in stock DMX.
# By default, use the Webclient front page.
DMX_FALLBACK_HEALTH_URL="${DMX_FALLBACK_HEALTH_URL:-${DMX_BASE_URL}/systems.dmx.webclient/}"

# Replace if your plugin exposes a different health endpoint.
DMX_DITA_HEALTH_URL="${DMX_DITA_HEALTH_URL:-${DMX_BASE_URL}/dmx/dita/health}"
# Replace with the actual run endpoint and payload keys for dmx-dita.
DMX_DITA_RUN_URL="${DMX_DITA_RUN_URL:-${DMX_BASE_URL}/dmx/dita/run}"

DITA_TEST_DIR="${DITA_TEST_DIR:-${ROOT_DIR}/testdata/dita/minimal}"
DITA_OUTPUT_DIR="${DITA_OUTPUT_DIR:-${ROOT_DIR}/test-output/dita/minimal}"

START_TIMEOUT="${START_TIMEOUT:-60}"
DITA_TIMEOUT="${DITA_TIMEOUT:-60}"
JAVA_REQUIRED_MAJOR="${JAVA_REQUIRED_MAJOR:-1.8}"
NODE_REQUIRED_MAJOR="${NODE_REQUIRED_MAJOR:-14}"

log() { printf '%s\n' "[$(date +%H:%M:%S)] $*" >&2; }
fail() { log "ERROR: $*"; exit 1; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"; }

check_java() {
  require_cmd java
  local v
  v=$(java -version 2>&1 | head -n1)
  log "Java: $v"
  if ! grep -E '1\.8|"8\.' <<<"$v" >/dev/null; then
    fail "Java 8 required; got: $v"
  fi
}

check_node() {
  require_cmd node
  require_cmd npm
  local v major
  v=$(node -v | sed 's/^v//')
  major=${v%%.*}
  log "Node: $v"
  if (( major < NODE_REQUIRED_MAJOR )); then
    fail "Node >= ${NODE_REQUIRED_MAJOR} required; got: $v"
  fi
}

resolve_dmx_cmd() {
  if [[ -n "${DMX_CMD:-}" ]]; then
    echo "$DMX_CMD"
    return
  fi
  if command -v dmx-run-backend >/dev/null 2>&1; then
    echo "$(command -v dmx-run-backend)"
    return
  fi
  echo "${DMX_ROOT_DEFAULT}/dmx-run-backend"
}

start_dmx() {
  require_cmd curl
  DMX_CMD="$(resolve_dmx_cmd)"
  [[ -x "$DMX_CMD" ]] || fail "DMX_CMD not executable: $DMX_CMD"
  mkdir -p "$DITA_OUTPUT_DIR"
  log "Starting DMX: $DMX_CMD"
  "$DMX_CMD" >"$DITA_OUTPUT_DIR/dmx.log" 2>&1 &
  DMX_PID=$!
  log "DMX PID: $DMX_PID"
}

stop_dmx() {
  if [[ -n "${DMX_PID:-}" ]] && kill -0 "$DMX_PID" 2>/dev/null; then
    log "Stopping DMX (PID $DMX_PID)"
    kill "$DMX_PID" || true
    wait "$DMX_PID" || true
  fi
}

wait_for_health() {
  log "Waiting for DMX health at ${DMX_HEALTH_URL} (fallback ${DMX_FALLBACK_HEALTH_URL}, timeout ${START_TIMEOUT}s)"
  local start now
  start=$(date +%s)

  while :; do
    # 1) Preferred: DMX_HEALTH_URL (future /dmx/version)
    if curl -sSf "$DMX_HEALTH_URL" >/dev/null 2>&1; then
      log "DMX is up via ${DMX_HEALTH_URL}"
      return 0
    fi

    # 2) Fallback: anything 2xx–4xx proves the app is responding.
    if [[ -n "${DMX_FALLBACK_HEALTH_URL:-}" ]]; then
      local code
      code=$(curl -sS -o /dev/null --write-out '%{http_code}' \
              "$DMX_FALLBACK_HEALTH_URL" 2>/dev/null || echo 0)
      if (( code >= 200 && code < 500 )); then
        log "DMX is up via fallback ${DMX_FALLBACK_HEALTH_URL} (HTTP ${code})"
        return 0
      fi
    fi

    now=$(date +%s)
    if (( now - start > START_TIMEOUT )); then
      fail "DMX did not become healthy; see $DITA_OUTPUT_DIR/dmx.log"
    fi

    sleep 2
  done
}

check_dita_plugin() {
  log "Checking dmx-dita at $DMX_DITA_HEALTH_URL"
  curl -sSf "$DMX_DITA_HEALTH_URL" >/dev/null 2>&1 \
    || fail "dmx-dita health failed at $DMX_DITA_HEALTH_URL"
  log "dmx-dita appears active."
}

run_dita_job() {
  log "Running minimal DITA job"
  local map_path="$DITA_TEST_DIR/map.ditamap"
  local out_dir="$DITA_OUTPUT_DIR/out"
  mkdir -p "$out_dir"

  # Adjust payload keys to match dmx-dita's API.
  local payload
  payload=$(cat <<EOF
{ "mapPath": "${map_path}", "outputDir": "${out_dir}", "format": "html5" }
EOF
)

  local resp="$DITA_OUTPUT_DIR/dita-run-response.json"
  if ! curl -sS -X POST \
      -H 'Content-Type: application/json' \
      -d "$payload" \
      "$DMX_DITA_RUN_URL" \
      -o "$resp"; then
    fail "DITA run request failed; see $resp and $DITA_OUTPUT_DIR/dmx.log"
  fi

  # Wait briefly for async processing; tune if needed.
  sleep 5

  if ! find "$out_dir" -name '*.html' -print -quit | grep -q .; then
    fail "No HTML output found in $out_dir after DITA run"
  fi

  log "Minimal DITA job succeeded; output in $out_dir"
}

trap stop_dmx EXIT

log "=== DMX + dmx-dita Environment Test ==="
check_java
check_node
start_dmx
wait_for_health
check_dita_plugin
run_dita_job
log "=== Environment Test PASSED ==="
