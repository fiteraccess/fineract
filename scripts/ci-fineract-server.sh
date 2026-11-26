#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Starts and stops a Fineract server on the CI runner for the test modules that need a live instance
# (integration-tests, twofactor-tests, oauth2-tests).
#
# Up to Fineract 1.15 the Gradle Cargo plugin started a Tomcat from inside each module's test task.
# Upstream removed that in 1.16: the build no longer starts a server. This script takes its place for
# the fork's workflows. It runs the Spring Boot jar on the runner itself, so the server reaches the
# database, LocalStack and the tests' mock servers on localhost exactly as the Cargo Tomcat did, and
# it listens on https://localhost:8443/fineract-provider, which is what the tests expect.
#
# Usage:
#   scripts/ci-fineract-server.sh start <basic|twofactor|oauth2>
#   scripts/ci-fineract-server.sh stop
#
# The database defaults in application.properties (PostgreSQL on localhost:5432, root/postgres,
# fineract_tenants / fineract_default) match the CI service container. The boot jar bundles the
# PostgreSQL driver only, so this script is for the PostgreSQL workflows.
#
# Server logs go to build/fineract-server/<mode>.log.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/build/fineract-server"
PID_FILE="$LOG_DIR/server.pid"
MODE_FILE="$LOG_DIR/server.mode"
HEALTH_URL="${FINERACT_CI_HEALTH_URL:-https://localhost:8443/fineract-provider/actuator/health}"
START_TIMEOUT_SECONDS="${FINERACT_CI_START_TIMEOUT_SECONDS:-900}"

# The same module access flags the Cargo configuration passed to the server JVM.
JVM_OPENS=(
  --add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.security=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.management/javax.management=ALL-UNNAMED
  --add-opens=java.naming/javax.naming=ALL-UNNAMED
)

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

stop_server() {
  if is_running; then
    local pid
    pid="$(cat "$PID_FILE")"
    echo "Stopping Fineract (pid $pid, mode $(cat "$MODE_FILE" 2>/dev/null || echo unknown))"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 60); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE" "$MODE_FILE"
}

find_jar() {
  find "$ROOT_DIR/fineract-provider/build/libs" -maxdepth 1 -type f -name 'fineract-provider-*.jar' \
    ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tail -n 1
}

start_server() {
  local mode="$1"
  local basicauth twofactor oauth2
  local -a mode_args=()

  case "$mode" in
    basic)
      basicauth=true; twofactor=false; oauth2=false
      # As the integration-tests Cargo configuration did.
      mode_args=(
        -Dfineract.synapse.enabled=true
        -Dfineract.synapse.base-url=http://localhost:18089
        -Dfineract.synapse.api-key=test-api-key
      )
      ;;
    twofactor)
      basicauth=true; twofactor=true; oauth2=false
      ;;
    oauth2)
      basicauth=false; twofactor=false; oauth2=true
      ;;
    *)
      echo "Unknown mode '$mode' (expected basic, twofactor or oauth2)" >&2
      exit 2
      ;;
  esac

  if is_running && [ "$(cat "$MODE_FILE" 2>/dev/null)" = "$mode" ]; then
    echo "Fineract is already running in $mode mode"
    return 0
  fi
  stop_server

  mkdir -p "$LOG_DIR"
  local jar
  jar="$(find_jar)"
  if [ -z "$jar" ]; then
    echo "Building the Fineract boot jar"
    (cd "$ROOT_DIR" && ./gradlew --no-daemon --console=plain :fineract-provider:bootJar)
    jar="$(find_jar)"
  fi
  if [ -z "$jar" ]; then
    echo "No fineract-provider boot jar found under fineract-provider/build/libs" >&2
    exit 1
  fi

  local log_file="$LOG_DIR/$mode.log"
  echo "Starting Fineract in $mode mode from $(basename "$jar") (log: $log_file)"

  # shellcheck disable=SC2086
  FINERACT_SECURITY_BASICAUTH_ENABLED="$basicauth" \
  FINERACT_SECURITY_2FA_ENABLED="$twofactor" \
  FINERACT_SECURITY_OAUTH_ENABLED="$oauth2" \
  nohup java "${JVM_OPENS[@]}" \
    -Dspring.profiles.active=test \
    -Dfineract.events.external.enabled=true \
    "${mode_args[@]}" \
    ${FINERACT_CI_JAVA_OPTS:-} \
    -jar "$jar" > "$log_file" 2>&1 &
  echo $! > "$PID_FILE"
  echo "$mode" > "$MODE_FILE"

  local waited=0
  until curl -fsk --max-time 5 "$HEALTH_URL" > /dev/null 2>&1; do
    if ! is_running; then
      echo "Fineract exited during startup; last lines of $log_file:" >&2
      tail -n 80 "$log_file" >&2 || true
      rm -f "$PID_FILE" "$MODE_FILE"
      exit 1
    fi
    if [ "$waited" -ge "$START_TIMEOUT_SECONDS" ]; then
      echo "Fineract did not answer $HEALTH_URL within ${START_TIMEOUT_SECONDS}s; last lines of $log_file:" >&2
      tail -n 80 "$log_file" >&2 || true
      stop_server
      exit 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "Fineract is up in $mode mode after ${waited}s"
}

case "${1:-}" in
  start)
    start_server "${2:-basic}"
    ;;
  stop)
    stop_server
    ;;
  *)
    echo "Usage: $0 start <basic|twofactor|oauth2> | stop" >&2
    exit 2
    ;;
esac
