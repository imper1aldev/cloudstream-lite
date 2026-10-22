#!/usr/bin/env bash
set -euo pipefail
[[ ${NEXO_CHANNEL:-} == stable || ${NEXO_CHANNEL:-} == prerelease ]] || { echo 'Invalid release channel' >&2; exit 1; }
[[ ${NEXO_VERSION_CODE:-} =~ ^[1-9][0-9]{0,9}$ ]] && (( NEXO_VERSION_CODE <= 2100000000 )) || { echo 'Invalid Android version code' >&2; exit 1; }
[[ ${NEXO_VERSION_NAME:-} =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]] || { echo 'Invalid version name' >&2; exit 1; }
for name in NEXO_KEYSTORE_BASE64 NEXO_KEY_ALIAS NEXO_KEY_PASSWORD NEXO_STORE_PASSWORD; do
  [[ -n ${!name:-} ]] || { printf 'Missing release signing secret: %s\n' "$name" >&2; exit 1; }
done
: "${RUNNER_TEMP:?Missing runner temporary directory}" "${GITHUB_ENV:?Missing runner environment file}"
umask 077
keyfile="$RUNNER_TEMP/nexostream-release.jks"
trap 'rm -f -- "$keyfile"' ERR
printf '%s' "$NEXO_KEYSTORE_BASE64" | base64 --decode > "$keyfile"
[[ -s "$keyfile" ]] || { echo 'Empty release keystore' >&2; rm -f -- "$keyfile"; exit 1; }
printf 'SIGNING_STORE_FILE=%s\n' "$keyfile" >> "$GITHUB_ENV"
