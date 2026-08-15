#!/usr/bin/env sh
# Generate a local CA and a certificate for the DevLoom domains.
#
# Not optional, and not about being tidy: .dev is on the HSTS preload list that ships inside every
# major browser, so a .dev name is rewritten to https:// before the request leaves the browser —
# and an HSTS domain gives you no "proceed anyway" button for an untrusted certificate. A .dev
# domain without a trusted certificate is unreachable, full stop.
#
# Hence a CA rather than a bare self-signed cert: you trust one certificate once, and every
# DevLoom name works, including any added later.
#
# Runs openssl in a container so nothing has to be installed on the host.
#
#   sh edge/make-certs.sh
#
# Then trust edge/certs/devloom-local-ca.crt (see README). The key never leaves this directory,
# and this CA can only vouch for the names below.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/certs"
mkdir -p "$OUT"

DOMAIN="${DEVLOOM_DOMAIN:-mycompanion-devloom.dev}"
echo "Generating a local CA and a certificate for $DOMAIN (+ *.$DOMAIN, localhost)"

docker run --rm -v "$OUT:/out" -w /out -e DOMAIN="$DOMAIN" alpine:3 sh -c '
  set -e
  apk add --no-cache openssl >/dev/null

  # The CA. 10 years, because a dev CA expiring silently is a morning lost to a confusing error.
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout devloom-local-ca.key -out devloom-local-ca.crt \
    -subj "/CN=DevLoom Local CA/O=DevLoom" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

  # The leaf. SANs only — browsers have ignored CN for years, so a cert without them is rejected
  # with an error that does not mention the missing SAN.
  cat > san.cnf <<EOF
[req]
distinguished_name = dn
[dn]
[ext]
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:$DOMAIN,DNS:*.$DOMAIN,DNS:localhost,IP:127.0.0.1
EOF

  openssl req -newkey rsa:2048 -sha256 -nodes -keyout server.key -out server.csr \
    -subj "/CN=$DOMAIN" 2>/dev/null
  openssl x509 -req -in server.csr -CA devloom-local-ca.crt -CAkey devloom-local-ca.key \
    -CAcreateserial -days 825 -sha256 -extfile san.cnf -extensions ext -out server.crt 2>/dev/null

  rm -f server.csr san.cnf
  chmod 644 server.crt server.key devloom-local-ca.crt
  echo "  wrote server.crt, server.key, devloom-local-ca.crt"
'

echo "Done. Trust edge/certs/devloom-local-ca.crt, then: docker compose up -d edge"
