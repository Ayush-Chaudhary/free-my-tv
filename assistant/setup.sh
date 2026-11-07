#!/usr/bin/env bash
# One-time setup (and token rotation) for the assistant's Worker. Run from your Mac, in assistant/.
#
#   ./setup.sh           make a new device token, store its hash on the Worker, and ask for the Groq and TMDB keys
#                        if the Worker doesn't have them yet
#   ./setup.sh --keys    also ask for the Groq and TMDB keys again (to replace them)
#
# The token is printed once, here. Only its SHA-256 goes to Cloudflare (the secret DEVICE_TOKEN_HASH). Rerunning
# makes a new token and the old one stops working at once. Needs: Node 18+, openssl, a Cloudflare account
# (free plan) and `npx wrangler login`. Run `npx wrangler deploy` first if the Worker isn't deployed yet.
set -euo pipefail
cd "$(dirname "$0")"

command -v openssl >/dev/null || { echo "openssl is needed." >&2; exit 1; }
[ -d node_modules ] || npm install --silent
WRANGLER="npx wrangler"

# 32 random bytes, base64url, no padding: 43 characters
token=$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')
hash=$(printf %s "$token" | openssl dgst -sha256 | awk '{print $NF}')

printf %s "$hash" | $WRANGLER secret put DEVICE_TOKEN_HASH >/dev/null
echo "Stored the token's hash on the Worker."

have=$($WRANGLER secret list 2>/dev/null || echo "[]")
ask_secret() {
  local name=$1 prompt=$2 value
  if [ "${REPLACE_KEYS:-0}" != 1 ] && printf %s "$have" | grep -q "\"$name\""; then
    echo "$name is already set (use ./setup.sh --keys to replace it)."
    return
  fi
  read -rsp "$prompt: " value; echo
  [ -n "$value" ] || { echo "Skipped $name." >&2; return; }
  printf %s "$value" | $WRANGLER secret put "$name" >/dev/null
  echo "Stored $name."
}
REPLACE_KEYS=0; [ "${1:-}" = "--keys" ] && REPLACE_KEYS=1
ask_secret GROQ_KEY "Groq API key (console.groq.com/keys, free)"
ask_secret TMDB_KEY "TMDB API key or read access token (themoviedb.org/settings/api, free)"

cat <<MSG

Your device token (shown once; keep it out of the repo):

  $token

Put it in config.properties as   assistant_token=<the token above>
(or type it on the TV later, in Home Settings > Assistant). The Worker's URL is the one
\`npx wrangler deploy\` printed, ending in .workers.dev.
MSG
