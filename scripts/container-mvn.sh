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

# A build is heavy: it runs in a CPU- and memory-capped scope at low priority
# where systemd's user manager is there, and at low priority anywhere else.
LIMIT=(nice -n 10)
if command -v systemd-run >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
  LIMIT=(systemd-run --user --scope --quiet -p CPUQuota=800% -p MemoryMax=16G nice -n 10)
fi

exec "${LIMIT[@]}" "$ENGINE" run --rm \
  -v "$ROOT:/work" \
  -v "$CACHE:/m2" \
  -w /work \
  "$IMAGE" \
  mvn -Dmaven.repo.local=/m2 "$@"
