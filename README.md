# Firmware Controller for Android

This is the single Android companion for the independent Heltec WiFi LoRa 32 V4
firmware builds in this workspace:

- Bruce in [`Unkl3Errl/HeltecFirmware`](https://github.com/Unkl3Errl/HeltecFirmware)
- GhostESP, adapted from the upstream `v2.1.1` stable release
- ESP32 Marauder in the independent
  [`heltec-v4-full`](https://github.com/Unkl3Errl/ESP32Marauder/tree/heltec-v4-full)
  branch

All three firmware tabs remain available while the app identifies connected
hardware. Detection opens the matching controller automatically, but it does
not merge firmware images or assume that the common Espressif USB ID identifies
a project.

Each application keeps its applicable transport controls visible: Bruce has
**Connect BruceNet** and **Reconnect USB**, GhostESP has **Connect GhostNet**
and **Reconnect USB**, and Marauder has **Reconnect USB**.

Each firmware tab also contains a recovery/update card. The app ships a
complete offset-0 bootable image for all three projects, verifies the catalog
signature and image SHA-256 values, and retains the verified images in private
Android app files. The flash action works from any currently installed firmware
or an empty flash, provided the target enumerates through the ESP32-S3 native
USB recovery interface. It verifies the target chip and ROM security state,
writes without a full-chip erase, checks the flashed MD5 on the device, and then
restarts and redetects the firmware.

Powered USB-hub operation allows more than one serial board to remain active.
When multiple boards
are attached, Android shows every compatible physical target and the app requires
an explicit choice. A read-only firmware probe binds the verified device to its
Bruce, GhostESP, or Marauder tab. Each physical board owns a separate persistent
session, including when two or more boards run the same firmware.
Labels include the USB product, serial number when Android exposes one, and
VID:PID. Without a serial number, Android's current USB path/device ID is shown.

The firmware tabs contain a second row of device tabs. Select a device tab to
route that screen's console and controls to that board; selecting another board
does not disconnect the first. Each device keeps an independent console state,
session history, storage transfer, and Android archive directory. Use **+ Add**
to verify another wired board or discover another supported Bluetooth device.

Flashing also presents the complete ESP32-S3 target list and repeats the exact
selected identity in the destructive-action confirmation. Only that target's
live serial session is released; other hub-connected boards continue running.
After the write and on-device MD5 verification, the app redetects the same
physical USB target. Use a powered USB-C OTG hub with Power Delivery pass-through
because several radio boards can exceed a phone's source-current budget.

At launch the app checks the signed catalog at
`Unkl3Errl/HeltecController`. New images are downloaded only from an
allowlisted GitHub HTTPS host and accepted only when the signed catalog size,
ESP32-S3 image header, and SHA-256 all match. The first visit to each tab shows
that release's version, date, and summary once. When a connected device reports
an older version, the tab changes to an update action and the app shows an
update notice.

The app separately queries each project's official GitHub `releases/latest`
endpoint over a validated internet connection. It checks at launch, whenever
the app resumes or a firmware tab is opened, every five minutes while the UI is
visible, and through Android's network-aware background scheduler at roughly
15-minute intervals. The last verified signed catalog and last valid upstream
release state remain visible offline. A system notification distinguishes a
new source release from a signed compatibility image that is actually ready to
flash. Android and GitHub may defer background jobs, so the next launch/resume
check is the immediate fallback.

Drafts, prereleases, nightlies, and tags that are not stable semantic versions
are ignored. If upstream is newer than the source baseline of the signed image,
the tab reports **compatibility build pending**; it never substitutes a generic
upstream binary for the customized Android-storage build.

## Firmware detection

USB detection opens the attached CDC serial port at 115200 baud and sends only
the read-only `info`, `help`, and `version` commands. It recognizes:

- Bruce from its `Bruce v...` or Heltec device identity response.
- Marauder from its ESP32 Marauder banner, `Firmware: Marauder` information,
  or distinctive command-list header.
- GhostESP from its Revival version banner, command-category heading, or
  `ghost>` prompt.

The detector closes the selected serial port before enabling the matching controller, so
the selected module can claim the port normally. Empty, unrelated, or
conflicting output leaves the app in **Unknown** state. Detaching a
USB-identified board retains its verified screen and session. GhostESP and
Bruce continue through an already-connected device Wi-Fi link; otherwise the
app shows an offline/standalone state. Reattaching a board triggers identity
verification and reopens USB automatically.

The former firmware-detection card is no longer shown. A slim status line
reports detection progress, and tapping the status badge opens **USB Detect**
plus BruceNet and GhostNet recovery actions.

Once a USB or local-device Wi-Fi session is opened, a low-priority Android
foreground service owns it independently of the visible screen. Switching apps,
locking the phone, or recreating the Activity therefore keeps the existing serial
descriptor and NetworkSpecifier request alive. The ongoing **Firmware device
session** notification returns to the controller; reopening the Activity attaches
to the live session instead of probing and reopening the device. Serial output
received while the screen is absent is buffered and delivered when it returns.
While the controller Activity is visible, any live USB or Bluetooth device keeps
the display awake automatically. The foreground service uses a partial wake lock
for active device transfers, so manually locking the display does not stop the
USB/Bluetooth storage drain.
On Android 13 and newer, allow the notification permission when first prompted so
that this ongoing background-session indicator remains visible.

## Continuous Android storage

Tap the status badge, choose **Choose Android storage**, and grant a folder from
Android's system picker. While a customized firmware is connected by USB, the
foreground service checks every connected device spool every ten seconds, continues with
the screen locked, and archives completed files beneath `Bruce`, `GhostESP`, or
`Marauder` in that folder. A stable per-device subdirectory prevents two boards
running the same firmware from overwriting one another.

Transfers are resumable and lossless: Android writes a stable partial document,
flushes it, verifies its byte count and CRC-32, finalizes it, verifies it again,
and only then sends an acknowledgment containing the same size and checksum.
The firmware recalculates both values before releasing its copy. A disconnect,
app restart, Android write failure, checksum mismatch, or full Android volume
therefore leaves the source segment on the board for retry.

Bruce field logs use dedicated 128 KiB NDJSON segments. GhostESP PCAP and
wardriving CSV writers and Marauder PCAP/log writers also roll at approximately
128 KiB, making older closed segments available while collection continues.
Active writer files, firmware configuration, scripts, themes, app assets, API
credentials, and Bruce's dedicated field-log directory are excluded from the
generic release path.

No finite spool can guarantee unlimited capture if the phone is disconnected,
its selected folder permission is revoked, or Android itself runs out of space.
In those states the firmware retains unacknowledged data instead of deleting it;
continuous operation depends on reconnecting a writable Android destination
before the remaining onboard reserve is consumed.

The Bruce, GhostESP, and Marauder tabs are always available. Each firmware view
keeps its own scroll position, and every verified USB or supported Bluetooth
device remains active while another firmware or device tab is visible. Multiple
boards may run the same firmware simultaneously. Switching between BruceNet and GhostNet replaces the current
Wi-Fi access-point request because Android can associate with only one of those
local APs at a time. Selecting a tab never disconnects either transport.

Bruce can use its BLE API service, and GhostESP can use the upstream GhostESP
Bridge GATT service. Android performs GATT discovery and subscription itself;
the user only enables Bluetooth, grants **Nearby devices**, and selects the
advertised board in the app. Manual pairing in Android Settings is not required.
USB remains preferred when USB and Bluetooth belong to the selected device.
The Marauder mobile image advertises its phone-facing UART GATT service for
commands, console output, and virtual-SD synchronization. GhostESP Bridge
requires its upstream GhostLink bridge-board arrangement.

Bruce can also be detected without USB. **Connect BruceNet** in the status menu requests the
default local-only `BruceNet` / `brucenet` network and verifies the Bruce WebUI
login signature at `http://172.0.0.1`.

GhostESP can likewise be detected without USB. **Connect GhostNet** in the status menu requests the
default local-only `GhostNet` / `GhostNet` network and verifies GhostESP
branding at `http://192.168.4.1`. Marauder can be detected through its advertised
Bluetooth UART service; it does not expose a device-local Wi-Fi network.

## Requirements

- Android 10 (API 29) or newer.
- A phone with USB host support and a data-capable USB-C OTG connection for USB
  detection and control. Use a powered USB-C OTG hub for simultaneous boards.
- Bruce WebUI mode for BruceNet operation.
- GhostESP's GhostNet access point for network operation.
- The customized Marauder firmware for its USB controller.

## Bruce interface

After Bruce is verified, the app automatically opens its USB link or retains
the verified BruceNet link. The interface provides:

- Authenticated board, firmware, battery, memory, Wi-Fi, BLE, GPS, logger, and
  LoRa status through BruceNet.
- GPS monitor controls; GPS, BLE, and Android-assisted Wi-Fi field logging;
  phone GPS assistance; file inventory; and Android document-picker export.
- Native rendering and navigation of Bruce's compiled vector display.
- LittleFS browsing, viewing/editing, creation, rename, delete, and download.
- A free-form 115200-baud USB CDC console with read-only shortcuts. Console
  commands are sent directly to the firmware without an app-level confirmation.
- SX1262 receive controls/history and firmware-constrained transmission. The
  transmit button sends immediately after validating that the payload is not
  blank.
- A dedicated **BruceNet Web UI** card with Connect, Refresh, and Open actions.
- The original WebUI in a network-bound embedded browser. An authenticated
  native session is handed to the WebView for seamless opening and removed
  from WebView storage when the browser closes.

Default Bruce values are:

| Setting | Default |
| --- | --- |
| Wi-Fi SSID | `BruceNet` |
| Wi-Fi password | `brucenet` |
| WebUI URL | `http://172.0.0.1` |
| WebUI username | `admin` |
| WebUI password | `bruce` |

## GhostESP interface

After GhostESP is verified, the app automatically opens its USB link or retains
the verified GhostNet link. The interface provides:

- A readable 115200-baud live console with page and live-follow controls.
- A private rolling console snapshot that survives activity recreation and USB
  detach/reattach, and is removed by Clear Console.
- A complete, unmodified free-form CLI input path over USB or `/api/command`.
- Quick diagnostic buttons for help, version, device status, chip information,
  GPS information, Wi-Fi status, and stop.
- GhostNet Web UI, settings, and log refresh through Android's device-local
  network binding, without taking over the phone's default Internet route.
- Direct opening of the local Web UI and Android document-picker console export.
- Automatic preference for USB when attached, with GhostNet retained as a
  fallback when USB is removed.

Default GhostESP values are:

| Setting | Default |
| --- | --- |
| Wi-Fi SSID | `GhostNet` |
| Wi-Fi password | `GhostNet` |
| WebUI URL | `http://192.168.4.1` |
| Web authentication | Disabled by firmware default |

## Marauder interface

After Marauder is verified, the app automatically reopens its USB port. The
Reconnect control remains available for permission or cable recovery. The app provides:

- A live 115200-baud console with reliable page and live-follow controls.
- A fixed, cutout-safe app header and firmware tabs with an independently
  scrolling interface below them.
- Screen rotation reflows that page without recreating the controller or
  disconnecting an active USB serial session.
- Read-only/help/GPS shortcuts plus user-controlled Wi-Fi and BLE discovery workflows.
  AP Scan first stops any scan left running from the OLED or CLI, clears old
  results, then runs continuously until Stop is pressed; Stop lists the APs.
- CLI output normalizes mixed serial line endings and uses increased line spacing
  so adjacent lines remain readable in both firmware consoles.
- Parsed AP and BLE result views with CSV export.
- Private, timestamped USB session history with secret redaction, view, rename,
  export, share, and delete actions.
- The complete source firmware command line. Commands are sent directly to the
  firmware without an app-level `AUTHORIZE` confirmation; the operator remains
  responsible for the command and target.

Marauder Bluetooth scanning remains a firmware feature, while the customized
mobile image also advertises a separate phone-facing UART GATT service for
Android commands, console output, and virtual-SD synchronization. USB remains
the preferred transport when it is connected.

## Bruce phone-assisted Wi-Fi logging

When Wi-Fi is enabled for a Bruce field-logger session, Android requests a
non-disruptive scan no more than once per minute. The app selects the 32
strongest unique BSSIDs, suppresses repeat observations for one minute, and
sends them to Bruce over authenticated BruceNet HTTP or the direct USB-C cable.
Android Wi-Fi and Location must be enabled, and the app must have the requested
nearby-Wi-Fi and location permissions.

The USB connection is a deliberately narrow application bridge. It supports
logger start, stop and status plus phone GPS and Wi-Fi observations; it does not
give the ESP32 unrestricted Android networking or Internet access. The Heltec
does not perform Wi-Fi scans while the phone-assisted source is active.

## Security and operating limits

- Detection never starts a scan, transmission, attack, or firmware update.
- WebUI and Wi-Fi credentials are held in memory and are not persisted.
- Bruce's WebView receives only the current in-memory session cookie, scoped to
  the configured local WebUI origin, and clears it when the embedded browser closes.
- Phone GPS fixes and Wi-Fi observations go only to the selected Bruce device
  over its local BruceNet or USB link.
- Exports are staged in private app storage before Android opens the selected
  document, and a failed export never asks the document provider to delete it.
- GhostESP and Bruce allow cleartext HTTP only for their device-local addresses,
  because both embedded Web UIs are HTTP-only.
- Use radio and network tools only on systems, devices, and spectrum you own or
  are explicitly authorized to test.

## Build and signing

From this directory with Android SDK 35 installed:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On macOS,
Gradle instead uses
`~/Library/Caches/HeltecController/app/outputs/apk/debug/app-debug.apk` to keep
concurrent build output outside File Provider-managed Documents folders.

The app ID is `com.unkl3errl.helteccontroller`, version `0.13.19`, code 51.
Release builds use the four `HELTEC_RELEASE_*` environment variables documented
in [`SIGNING.md`](SIGNING.md). On the provisioned macOS development machine,
`./scripts/build-release-macos.sh` loads the project-specific signing password
from Keychain and builds with the permanent v2 identity. Without all four
variables, Gradle deliberately produces an unsigned release.

Maintainers should use [`RELEASING.md`](RELEASING.md) and
`./scripts/package-release-macos.sh` to run the complete release gate and create
the signed, versioned APK plus its SHA-256 checksum for a GitHub release.

## Project information

- Except where a file says otherwise, the original Android controller source is
  copyright 2026 Unkl3Errl and contributors and is licensed under
  [GPL-3.0-or-later](LICENSE).
- The bundled firmware images retain their own licenses and exact corresponding
  source links; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
- Read [`SUPPORT.md`](SUPPORT.md) before opening a support issue.
- See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the build, test, compatibility,
  and safety requirements for pull requests.
- Report vulnerabilities privately according to [`SECURITY.md`](SECURITY.md).
