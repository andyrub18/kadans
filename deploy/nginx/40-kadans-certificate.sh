#!/bin/sh
# Run by the nginx image's entrypoint before nginx starts (/docker-entrypoint.d). HTTPS is served as soon as certbot
# has the certificate: on the very first start that takes about a minute, afterwards it is there from the start. A
# watcher then reloads nginx within a minute of every new or renewed certificate.
set -eu

cert="/etc/letsencrypt/live/$KADANS_DOMAIN/fullchain.pem"
https=/etc/nginx/conf.d/https.conf

rm -f /etc/nginx/conf.d/default.conf # the image's welcome page

enable_https() { envsubst '${KADANS_DOMAIN}' < /etc/nginx/kadans/https.conf.template > "$https"; }
changed_at() { stat -L -c %Y "$cert" 2> /dev/null || echo none; }

if [ -s "$cert" ]; then
    enable_https
else
    rm -f "$https"
    echo "kadans: no certificate yet, HTTPS starts once certbot has one"
fi

(
    seen=$(changed_at)
    while sleep 60; do
        now=$(changed_at)
        if [ "$now" != none ] && [ "$now" != "$seen" ]; then
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
