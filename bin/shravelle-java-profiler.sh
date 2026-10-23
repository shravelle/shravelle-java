#!/usr/bin/env sh
# Continuous profiling for any JVM (Java, Kotlin, Scala) with async-profiler.
#
#   SHRAVELLE_API_KEY=shv_ing_... SHRAVELLE_SERVICE_NAME=orders ./shravelle-java-profiler.sh <pid>
#   # or run next to the app in a container/sidecar (shareProcessNamespace) with the pid of the JVM
#
# Every SHRAVELLE_PROFILE_INTERVAL seconds (60) it records, one after another:
#   cpu          SHRAVELLE_PROFILE_SECONDS (10 s) of on-CPU samples (Java + native + kernel frames)
#   alloc_space  5 s of bytes allocated per stack (TLAB-based sampling, low overhead)
#   lock_delay   5 s of time spent waiting for monitors (ns)
# and uploads them as folded stacks to POST /v1/profiles. Needs Linux or macOS and
# permission to attach to the JVM (same user, or root). Pin ASYNC_PROFILER_VERSION in production.
set -eu
PID="${1:?usage: shravelle-java-profiler.sh <jvm-pid>}"
ENDPOINT="${SHRAVELLE_ENDPOINT:-http://localhost:4318}"
KEY="${SHRAVELLE_API_KEY:?SHRAVELLE_API_KEY is required}"
SERVICE="${SHRAVELLE_SERVICE_NAME:?SHRAVELLE_SERVICE_NAME is required}"
VER="${ASYNC_PROFILER_VERSION:-3.0}"
INTERVAL="${SHRAVELLE_PROFILE_INTERVAL:-60}"
SECS="${SHRAVELLE_PROFILE_SECONDS:-10}"
DIR="${SHRAVELLE_HOME:-$HOME/.shravelle}/async-profiler-$VER"
HOST="$(hostname)"

if [ ! -x "$DIR/bin/asprof" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)  PKG="async-profiler-$VER-linux-x64.tar.gz" ;;
    Linux-aarch64) PKG="async-profiler-$VER-linux-arm64.tar.gz" ;;
    Darwin-*)      PKG="async-profiler-$VER-macos.zip" ;;
    *) echo "unsupported platform $(uname -s)-$(uname -m)"; exit 1 ;;
  esac
  mkdir -p "$DIR" && cd "$DIR"
  curl -fsSL -o "$PKG" "https://github.com/async-profiler/async-profiler/releases/download/v$VER/$PKG"
  case "$PKG" in *.zip) unzip -q "$PKG" ;; *) tar xzf "$PKG" ;; esac
  inner="$(find . -maxdepth 1 -type d -name 'async-profiler-*' | head -1)"; [ -n "$inner" ] && cp -R "$inner"/* . 
  cd - >/dev/null
fi

upload() { # $1=type $2=unit $3=extra-params $4=file
  [ -s "$4" ] || return 0
  curl -fsS -m 30 -X POST "$ENDPOINT/v1/profiles?service=$SERVICE&env=${SHRAVELLE_ENV:-production}&version=${SHRAVELLE_SERVICE_VERSION:-}&host=$HOST&language=java&type=$1&unit=$2&duration=$SECS$3" \
    -H "X-API-Key: $KEY" -H "Content-Type: text/plain" --data-binary @"$4" >/dev/null || echo "shravelle: upload of $1 failed (will retry next interval)"
}

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
while kill -0 "$PID" 2>/dev/null; do
  start=$(date +%s)
  # cpu falls back to itimer where perf_events are not allowed (containers)
  "$DIR/bin/asprof" -d "$SECS" -e cpu -i 10ms -o collapsed -f "$TMP/cpu.txt" "$PID" >/dev/null 2>&1 || \
    "$DIR/bin/asprof" -d "$SECS" -e itimer -i 10ms -o collapsed -f "$TMP/cpu.txt" "$PID" >/dev/null 2>&1 || true
  upload cpu samples "&interval_ms=10" "$TMP/cpu.txt"
  "$DIR/bin/asprof" -d 5 -e alloc --total -o collapsed -f "$TMP/alloc.txt" "$PID" >/dev/null 2>&1 && upload alloc_space bytes "" "$TMP/alloc.txt" || true
  "$DIR/bin/asprof" -d 5 -e lock --total -o collapsed -f "$TMP/lock.txt" "$PID" >/dev/null 2>&1 && upload lock_delay nanoseconds "" "$TMP/lock.txt" || true
  rm -f "$TMP"/*.txt
  sleep $(( INTERVAL - ( $(date +%s) - start ) > 1 ? INTERVAL - ( $(date +%s) - start ) : 1 ))
done
echo "shravelle: JVM $PID exited"
