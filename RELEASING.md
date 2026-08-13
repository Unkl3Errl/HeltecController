# Release checklist

HeltecController direct-download releases must remain on the permanent v2
signing identity documented in [`SIGNING.md`](SIGNING.md). Never commit or
upload the keystore or its password.

## Before packaging

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, and update
   the version shown in `README.md`.
2. Run the unit tests, lint, and both debug and minified release builds.
3. Test an in-place upgrade over the preceding signed release.
4. Test USB attach/detach, each firmware tab, console scrolling, the guarded
   command dialogs, and any changed local Wi-Fi workflow on a physical phone.
5. For Android storage changes, verify a transfer with the screen locked and
   confirm that the board retains its source after an interrupted or invalid
   transfer and releases it only after Android verifies the final size and
   CRC-32.
6. Rebuild all three complete firmware images, update `../firmware-catalog.json`
   with their exact source commits, sizes, and SHA-256 values, and place the
   matching assets under `app/src/main/assets/firmware/`.
7. Test a firmware change through the Android recovery card and confirm its
   ROM MD5 verification, reboot, version redetection, and **Current Device
   Firmware** state. Do this without erasing any pending device spool data.

## Package the signed APK

On the provisioned macOS signing machine, run:

```sh
./scripts/package-release-macos.sh
```

The script signs the firmware catalog with the permanent release identity,
copies the signed catalog into the APK and the public repository root, retrieves
the password from Login
Keychain, runs tests and lint,
builds the minified release, verifies the APK signature against the permanent
v2 certificate, and writes these ignored local artifacts:

```text
dist/HeltecController-<version>.apk
dist/HeltecController-<version>.apk.sha256
```

Install that exact APK with `adb install -r` and perform one final smoke test.
Then create the GitHub release from the reviewed commit/tag and attach both
APK files plus all three versioned firmware files from
`app/src/main/assets/firmware/`. Those public assets back in-app updates even
when an individual source repository is private. The normal GitHub Actions
build intentionally publishes only an unsigned
release APK because the private signing identity is not stored in the
repository.

## Firmware and parent repository

Bruce, GhostESP, Marauder, and HeltecController are independent repositories.
Push and review each submodule change on its intended fork branch, wait for its
own CI build, and publish firmware images from that repository. After those
commits exist remotely, update the parent `Heltec-Pentest-Firmware` repository's
four submodule pointers and release notes. Do not publish a parent commit that
references submodule commits that have not been pushed.
