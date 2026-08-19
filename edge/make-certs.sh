#!/usr/bin/env sh
# Manual wrapper around edge/gen-certs.sh — generates the local CA + certificate by running
# openssl in a container, so nothing has to be installed on the host:
#
#   sh edge/make-certs.sh
#
# You normally don't need this: the `edge-certs` one-shot service in both compose files runs the
# same script on every `up`, so a fresh machine gets certificates automatically. This wrapper
# exists for generating them ahead of time (e.g. to trust the CA before first launch) or after
# deleting edge/certs to rotate. Trust edge/certs/devloom-local-ca.crt afterwards — see the
# README's "Using the domain names".
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
docker run --rm -v "$DIR:/edge" -e DEVLOOM_DOMAIN="${DEVLOOM_DOMAIN:-mycompanion-devloom.dev}" \
  alpine:3 sh /edge/gen-certs.sh /edge/certs
