#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
catalog=${1:-"$project_dir/../firmware-catalog.json"}
signature=${2:-"$project_dir/../firmware-catalog.sig"}
signing_store="$HOME/Library/Application Support/HeltecController/signing/heltec-controller-release-v2.p12"
keychain_service="HeltecController Release Signing v2"
temporary_dir=$(mktemp -d)
private_key="$temporary_dir/catalog-signing-key.pem"
public_key="$temporary_dir/catalog-signing-public.pem"

cleanup() {
    rm -f "$private_key" "$public_key"
    rmdir "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM
chmod 700 "$temporary_dir"

signing_password=$(security find-generic-password \
    -a "$(id -un)" \
    -s "$keychain_service" \
    -w)
export CATALOG_SIGNING_PASSWORD=$signing_password
unset signing_password

openssl pkcs12 \
    -in "$signing_store" \
    -nocerts \
    -nodes \
    -passin env:CATALOG_SIGNING_PASSWORD \
    -out "$private_key" >/dev/null 2>&1
chmod 600 "$private_key"
openssl dgst -sha256 -sign "$private_key" -out "$signature" "$catalog"
openssl pkcs12 \
    -in "$signing_store" \
    -clcerts \
    -nokeys \
    -passin env:CATALOG_SIGNING_PASSWORD 2>/dev/null |
    openssl x509 -pubkey -noout > "$public_key"
openssl dgst -sha256 -verify "$public_key" -signature "$signature" "$catalog" >/dev/null
unset CATALOG_SIGNING_PASSWORD

echo "Signed firmware catalog: $signature"
