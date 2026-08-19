#!/bin/sh
# Generates the local CA + server certificate for the edge. Runs INSIDE an alpine container —
# invoked by the `edge-certs` one-shot compose service on every `up` (idempotent), and by
# edge/make-certs.sh for a manual run. It exists as a compose service because the failure mode
# of a missing cert is nasty-silent: nginx crash-loops while `up -d` happily prints "Started",
# and the whole app is unreachable with no error anywhere the user is looking.
#
# Why a CA rather than a bare self-signed cert: you trust one certificate once and every DevLoom
# name works, including ones added later. The key never leaves edge/certs/ (gitignored), and the
# CA can only vouch for the names below.
set -e

OUT="${1:-/edge/certs}"
DOMAIN="${DEVLOOM_DOMAIN:-mycompanion-devloom.dev}"

if [ -f "$OUT/server.crt" ] && [ -f "$OUT/server.key" ]; then
  echo "edge-certs: certificates already present in $OUT — nothing to do"
  exit 0
fi

echo "edge-certs: generating a local CA and a certificate for $DOMAIN (+ *.$DOMAIN, localhost)"
apk add --no-cache openssl >/dev/null
mkdir -p "$OUT"
cd "$OUT"

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
echo "edge-certs: wrote server.crt, server.key, devloom-local-ca.crt"
echo "edge-certs: to use the https://$DOMAIN names, trust devloom-local-ca.crt (README: 'Using the domain names')"
