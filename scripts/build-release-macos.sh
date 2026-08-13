#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
signing_store="$HOME/Library/Application Support/HeltecController/signing/heltec-controller-release-v2.p12"
keychain_service="HeltecController Release Signing v2"

if [ ! -f "$signing_store" ]; then
    echo "Heltec Controller release keystore is missing: $signing_store" >&2
    exit 1
fi

signing_password=$(security find-generic-password \
    -a "$(id -un)" \
    -s "$keychain_service" \
    -w)

export HELTEC_RELEASE_STORE_FILE="$signing_store"
export HELTEC_RELEASE_STORE_PASSWORD="$signing_password"
export HELTEC_RELEASE_KEY_ALIAS="helteccontroller"
export HELTEC_RELEASE_KEY_PASSWORD="$signing_password"
unset signing_password

if [ "$#" -eq 0 ]; then
    set -- testDebugUnitTest assembleRelease
fi

exec "$project_dir/gradlew" -p "$project_dir" "$@"
