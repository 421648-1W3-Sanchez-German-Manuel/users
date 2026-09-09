#!/usr/bin/env bash
# Generates a development RSA key pair under ./secrets (DEC-18).
#
# You only need this to RUN the service locally. The test suite does NOT: it
# generates its own throwaway keys (see AbstractIntegrationTest), so
# `mvn -q clean verify` works on a fresh clone with no setup.
set -euo pipefail

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is not on your PATH." >&2
  echo >&2
  echo "On Windows it is not there by default, but Git ships it:" >&2
  echo "    C:\Program Files\Git\usr\bin\openssl.exe" >&2
  echo >&2
  echo "Run this script from Git Bash, which already has it, or add that" >&2
  echo "directory to your PATH." >&2
  exit 1
fi

KID="${1:-dev}"
mkdir -p secrets/jwks

if [ -f "secrets/jwt-private.pem" ]; then
  echo "secrets/jwt-private.pem already exists; it will not be overwritten."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl rsa -in secrets/jwt-private.pem -pubout -out "secrets/jwks/${KID}.pem"

echo "Key pair generated. Export JWT_ACTIVE_KID=${KID}"
