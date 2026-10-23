#!/usr/bin/env sh
# Zero-code Java instrumentation for Shravelle.
#   SHRAVELLE_API_KEY=shv_ing_... SHRAVELLE_SERVICE_NAME=checkout ./shravelle-java-agent.sh java -jar app.jar
# Downloads the OpenTelemetry Java agent once (pin OTEL_AGENT_VERSION in production) and runs your command with it.
set -e
DIR="${SHRAVELLE_HOME:-$HOME/.shravelle}"
VER="${OTEL_AGENT_VERSION:-v2.6.0}"
JAR="$DIR/opentelemetry-javaagent-$VER.jar"
mkdir -p "$DIR"
[ -f "$JAR" ] || curl -fsSL -o "$JAR" "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/$VER/opentelemetry-javaagent.jar"
export OTEL_SERVICE_NAME="${SHRAVELLE_SERVICE_NAME:-${OTEL_SERVICE_NAME:-java-app}}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${SHRAVELLE_ENDPOINT:-http://localhost:4318}"
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_EXPORTER_OTLP_HEADERS="x-api-key=${SHRAVELLE_API_KEY:?SHRAVELLE_API_KEY is required}"
export OTEL_EXPORTER_OTLP_COMPRESSION=gzip
export OTEL_LOGS_EXPORTER=otlp
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment.name=${SHRAVELLE_ENV:-production},service.version=${SHRAVELLE_SERVICE_VERSION:-},telemetry.distro.name=shravelle-java-agent${OTEL_RESOURCE_ATTRIBUTES:+,$OTEL_RESOURCE_ATTRIBUTES}"
export JAVA_TOOL_OPTIONS="-javaagent:$JAR ${JAVA_TOOL_OPTIONS:-}"
exec "$@"
