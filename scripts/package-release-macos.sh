#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
catalog="$project_dir/../firmware-catalog.json"
catalog_signature="$project_dir/../firmware-catalog.sig"
bundled_catalog="$project_dir/app/src/main/assets/firmware/catalog.json"
bundled_signature="$project_dir/app/src/main/assets/firmware/catalog.sig"
public_catalog="$project_dir/firmware-catalog.json"
public_signature="$project_dir/firmware-catalog.sig"
android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools_version=$(find "$android_sdk/build-tools" -mindepth 1 -maxdepth 1 -type d \
    -exec basename {} \; | sort | tail -n 1)
build_tools="$android_sdk/build-tools/$build_tools_version"
source_apk="$HOME/Library/Caches/HeltecController/app/outputs/apk/release/app-release.apk"
expected_certificate="372baeb4329b91789654b372cb9fb0fe954739f1582849a3592347a7232fdfb8"

"$script_dir/sign-firmware-catalog-macos.sh" "$catalog" "$catalog_signature"
install -m 0644 "$catalog" "$bundled_catalog"
install -m 0644 "$catalog_signature" "$bundled_signature"
install -m 0644 "$catalog" "$public_catalog"
install -m 0644 "$catalog_signature" "$public_signature"

"$script_dir/build-release-macos.sh" testDebugUnitTest lintDebug assembleRelease

"$build_tools/apksigner" verify --verbose "$source_apk"
actual_certificate=$("$build_tools/apksigner" verify --print-certs "$source_apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
if [ "$actual_certificate" != "$expected_certificate" ]; then
    echo "Release certificate does not match the permanent HeltecController v2 identity." >&2
    exit 1
fi

version_name=$("$build_tools/aapt" dump badging "$source_apk" |
    sed -n "s/.* versionName='\([^']*\)'.*/\1/p" | head -n 1)
if [ -z "$version_name" ]; then
    echo "Could not read the APK version name." >&2
    exit 1
fi

dist_dir="$project_dir/dist"
release_apk="$dist_dir/HeltecController-$version_name.apk"
mkdir -p "$dist_dir"
install -m 0644 "$source_apk" "$release_apk"
(
    cd "$dist_dir"
    shasum -a 256 "$(basename "$release_apk")" > "$(basename "$release_apk").sha256"
)

echo "Packaged signed release: $release_apk"
echo "Checksum: $release_apk.sha256"
