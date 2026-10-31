#!/bin/sh
# Run by the nginx image's entrypoint before nginx starts (/docker-entrypoint.d). HTTPS is served as soon as certbot
# has a certificate: on the very first start that takes about a minute, afterwards it is there from the start. A
# watcher then reloads nginx within a minute of every new or renewed certificate. Two sites: the API ($KADANS_DOMAIN,
# its port-80 half from templates/), and the website ($KADANS_SITE_DOMAIN, both halves from here) when that is set.
set -eu

site="${KADANS_SITE_DOMAIN:-}"
api_cert="/etc/letsencrypt/live/$KADANS_DOMAIN/fullchain.pem"
site_cert="/etc/letsencrypt/live/$site/fullchain.pem"
api_https=/etc/nginx/conf.d/https.conf
site_http=/etc/nginx/conf.d/site.conf
site_https=/etc/nginx/conf.d/site-https.conf

rm -f /etc/nginx/conf.d/default.conf # the image's welcome page

# Each HTTPS half is there once its certificate is, and only then: nginx refuses to start on a missing one.
enable_https() {
    if [ -s "$api_cert" ]; then
        envsubst '${KADANS_DOMAIN}' < /etc/nginx/kadans/https.conf.template > "$api_https"
    else
        rm -f "$api_https"
    fi
    if [ -n "$site" ] && [ -s "$site_cert" ]; then
        envsubst '${KADANS_SITE_DOMAIN}' < /etc/nginx/kadans/site-https.conf.template > "$site_https"
    else
        rm -f "$site_https"
    fi
}
changed_at() { echo "$(stat -L -c %Y "$api_cert" 2> /dev/null || echo none) $(stat -L -c %Y "$site_cert" 2> /dev/null || echo none)"; }

if [ -n "$site" ]; then
    envsubst '${KADANS_SITE_DOMAIN}' < /etc/nginx/kadans/site.conf.template > "$site_http"
else
    rm -f "$site_http"
fi
enable_https
[ -s "$api_cert" ] || echo "kadans: no certificate for $KADANS_DOMAIN yet, its HTTPS starts once certbot has one"
if [ -n "$site" ] && [ ! -s "$site_cert" ]; then
    echo "kadans: no certificate for $site yet, its HTTPS starts once certbot has one"
fi

(
    seen=$(changed_at)
    while sleep 60; do
        now=$(changed_at)
        if [ "$now" != "$seen" ]; then
            enable_https
            if nginx -t -q; then
                nginx -s reload && echo "kadans: new certificate, nginx reloaded"
                seen=$now
            else
                echo "kadans: new certificate, but the configuration does not test; still serving the previous one" >&2
            fi
        fi
    done
) &
