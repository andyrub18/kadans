#!/bin/sh
# Let's Encrypt for nginx (docs/DEPLOYMENT.md → Proxy and certificates): the certificates on the first start, then a
# renewal check twice a day (certbot renews well before expiry). nginx picks a new certificate up within a minute. The
# expiry dates go to Prometheus through node-exporter's textfile collector: an alert fires 14 days before the first, or
# while one is missing. Two certificates: the API's ($KADANS_DOMAIN), and the website's ($KADANS_SITE_DOMAIN and its
# www) when that is set.
set -u
api_cert="/etc/letsencrypt/live/$KADANS_DOMAIN/fullchain.pem"
site="${KADANS_SITE_DOMAIN:-}"
site_cert="/etc/letsencrypt/live/$site/fullchain.pem"

publish_expiry() {
    python3 - "$@" > /metrics/tls.prom.tmp << 'PY' && mv /metrics/tls.prom.tmp /metrics/tls.prom
import sys
from cryptography import x509

print("# TYPE kadans_tls_certificate_expiry_timestamp_seconds gauge")
for name, path in zip(sys.argv[1::2], sys.argv[2::2]):
    expiry = 0  # missing: long expired, so the alert fires
    if path != "-":
        with open(path, "rb") as pem:
            expiry = int(x509.load_pem_x509_certificate(pem.read()).not_valid_after_utc.timestamp())
    print(f'kadans_tls_certificate_expiry_timestamp_seconds{{domain="{name}"}} {expiry}')
PY
}

# A certificate named after its first domain, for those domains. nginx answers the challenge on port 80: the DNS
# records must already point here (DNS only on Cloudflare).
obtain() {
    name=$1
    shift
    [ -s "/etc/letsencrypt/live/$name/fullchain.pem" ] && return 0
    domains=""
    for domain in "$@"; do domains="$domains -d $domain"; done
    # shellcheck disable=SC2086 # one -d per domain
    certbot certonly --webroot -w /var/www/acme --cert-name "$name" $domains --non-interactive --agree-tos \
        --register-unsafely-without-email --key-type ecdsa
}

while :; do
    obtain "$KADANS_DOMAIN" "$KADANS_DOMAIN"
    [ -n "$site" ] && obtain "$site" "$site" "www.$site"
    certbot renew --webroot -w /var/www/acme --quiet

    certificates="" missing=""
    if [ -s "$api_cert" ]; then
        certificates="$KADANS_DOMAIN $api_cert"
    else
        missing=yes # no metric at all yet: the alert's "absent" covers the API's
    fi
    if [ -n "$site" ]; then
        if [ -s "$site_cert" ]; then certificates="$certificates $site $site_cert"; else certificates="$certificates $site -" missing=yes; fi
    fi
    # shellcheck disable=SC2086 # name and path pairs
    [ -n "$certificates" ] && publish_expiry $certificates
    if [ -n "$missing" ]; then
        sleep 900 # until every first certificate: Let's Encrypt allows 5 failed validations an hour
    else
        sleep 43200
    fi
done
