#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <apk>" >&2
    exit 2
fi

apk=$1
if [ ! -f "$apk" ]; then
    echo "APK not found: $apk" >&2
    exit 1
fi

android_sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools_version=$(find "$android_sdk/build-tools" -mindepth 1 -maxdepth 1 -type d \
    -exec basename {} \; | sort | tail -n 1)
build_tools="$android_sdk/build-tools/$build_tools_version"
badging_file=$(mktemp "${TMPDIR:-/tmp}/firmware-controller-badging.XXXXXX")
resources_file=$(mktemp "${TMPDIR:-/tmp}/firmware-controller-resources.XXXXXX")
manifest_file=$(mktemp "${TMPDIR:-/tmp}/firmware-controller-manifest.XXXXXX")
trap 'rm -f -- "$badging_file" "$resources_file" "$manifest_file"' EXIT

"$build_tools/aapt" dump badging "$apk" > "$badging_file"
"$build_tools/aapt2" dump resources "$apk" > "$resources_file"
"$build_tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml > "$manifest_file"

if ! grep -Eq "application-icon-[0-9]+:'res/.+\.xml'" "$badging_file"; then
    echo "The APK does not expose the adaptive launcher icon." >&2
    exit 1
fi

icon_id=$(sed -n 's/.*android:icon.*=@\(0x[0-9a-f]*\).*/\1/p' "$manifest_file" | head -n 1)
round_icon_id=$(sed -n 's/.*android:roundIcon.*=@\(0x[0-9a-f]*\).*/\1/p' "$manifest_file" | head -n 1)
if [ -z "$icon_id" ] || ! grep -F "resource $icon_id mipmap/ic_launcher" "$resources_file" >/dev/null; then
    echo "The manifest launcher icon does not resolve to mipmap/ic_launcher." >&2
    exit 1
fi
if [ -z "$round_icon_id" ] || ! grep -F "resource $round_icon_id mipmap/ic_launcher_round" "$resources_file" >/dev/null; then
    echo "The manifest round icon does not resolve to mipmap/ic_launcher_round." >&2
    exit 1
fi

for resource_name in \
    color/ic_launcher_background \
    drawable/ic_launcher_foreground \
    drawable/ic_launcher_monochrome \
    mipmap/ic_launcher \
    mipmap/ic_launcher_round
do
    if ! grep -F "$resource_name" "$resources_file" >/dev/null; then
        echo "The APK is missing $resource_name." >&2
        exit 1
    fi
done

echo "Packaged Android launcher icon verification passed: $apk"
