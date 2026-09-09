#!/usr/bin/env bash
# Generates a development RSA key pair under ./secrets (DEC-18).
set -euo pipefail

KID="${1:-dev}"
mkdir -p secrets/jwks

if [ -f "secrets/jwt-private.pem" ]; then
  echo "secrets/jwt-private.pem already exists; it will not be overwritten."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl rsa -in secrets/jwt-private.pem -pubout -out "secrets/jwks/${KID}.pem"

echo "Key pair generated. Export JWT_ACTIVE_KID=${KID}"
