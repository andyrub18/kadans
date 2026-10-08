#!/bin/sh
# Let's Encrypt for nginx (docs/DEPLOYMENT.md → Proxy and certificates): the certificate on the first start, then a
# renewal check twice a day (certbot renews well before expiry). nginx picks a new certificate up within a minute. The
# expiry date goes to Prometheus through node-exporter's textfile collector: an alert fires 14 days before it.
set -u
cert="/etc/letsencrypt/live/$KADANS_DOMAIN/fullchain.pem"

publish_expiry() {
    python3 - "$cert" > /metrics/tls.prom.tmp << 'PY' && mv /metrics/tls.prom.tmp /metrics/tls.prom
import sys
from cryptography import x509

with open(sys.argv[1], "rb") as pem:
    expiry = x509.load_pem_x509_certificate(pem.read()).not_valid_after_utc
print("# TYPE kadans_tls_certificate_expiry_timestamp_seconds gauge")
print(f"kadans_tls_certificate_expiry_timestamp_seconds {int(expiry.timestamp())}")
PY
}

while :; do
    if [ -s "$cert" ]; then
        certbot renew --webroot -w /var/www/acme --quiet
    else
        # nginx answers the challenge on port 80: the DNS record must already point here (DNS only on Cloudflare).
        certbot certonly --webroot -w /var/www/acme -d "$KADANS_DOMAIN" --non-interactive --agree-tos \
            --register-unsafely-without-email --key-type ecdsa
    fi
    if [ -s "$cert" ]; then
        publish_expiry
        sleep 43200
    else
        sleep 900 # until the first certificate: Let's Encrypt allows 5 failed validations an hour
    fi
done
