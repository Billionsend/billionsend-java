#!/usr/bin/env bash
# Runs Maven in the pinned official image, with the repository mounted and a
# Maven cache outside the home directory, so nothing has to be installed on the
# host. Arguments go to mvn (default: -B verify).
#
#   scripts/container-mvn.sh                 # build and run every test
#   scripts/container-mvn.sh -B -q test -Dtest=WebhookVerifierTest
#   MAVEN_CACHE=/tmp/m2 scripts/container-mvn.sh
set -euo pipefail

IMAGE="${MAVEN_IMAGE:-docker.io/library/maven:3.9.9-eclipse-temurin-17}"
ENGINE="${CONTAINER_ENGINE:-podman}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${MAVEN_CACHE:-$ROOT/.m2}"
mkdir -p "$CACHE"

if [ "$#" -eq 0 ]; then
  set -- -B verify
fi

exec "$ENGINE" run --rm \
  -v "$ROOT:/work" \
  -v "$CACHE:/m2" \
  -w /work \
  "$IMAGE" \
  mvn -Dmaven.repo.local=/m2 "$@"
