#!/bin/sh
set -e

# Ensure URL doesn't end with a slash
TOURN_URL=$(echo "${TOURNAMENT_URL:-https://tournament.staging.maichess.berger-software.com}" | sed 's|/$||')
# Extract host from URL
HOST=$(echo "$TOURN_URL" | sed -E 's|https?://([^/]+).*|\1|')

# Extract nameserver from /etc/resolv.conf (useful in K8s/Docker)
NAMESERVER=$(grep -i nameserver /etc/resolv.conf | awk '{print $2}' | tr '\n' ' ')
if [ -z "$NAMESERVER" ]; then
    NAMESERVER="8.8.8.8 1.1.1.1 127.0.0.11"
fi

# Replace placeholders from the template and generate the final config
sed -e "s|__TOURNAMENT_URL__|${TOURN_URL}|g" \
    -e "s|__TOURNAMENT_HOST__|${HOST}|g" \
    -e "s|__NAMESERVER__|${NAMESERVER}|g" \
    /etc/nginx/nginx.conf.template > /etc/nginx/conf.d/default.conf

# Execute the main process
exec nginx -g "daemon off;"
