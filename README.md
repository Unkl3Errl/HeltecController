# Heltec Firmware Controller for Android

This is the single Android companion for the independent Heltec WiFi LoRa 32 V4
firmware builds in this workspace:

- GhostESP, adapted from the upstream `Development-deki` branch
- ESP32 Marauder in [`Unkl3Errl/ESP32Marauder`](https://github.com/Unkl3Errl/ESP32Marauder)

The app locks both visible firmware tabs until it identifies the connected
firmware. The existing Bruce implementation remains in source for later reuse,
but its detection button, tab, and controls are intentionally hidden in this
release. The app does not merge firmware images or assume that the common
Espressif USB ID identifies a project.

## Firmware detection

USB detection opens the attached CDC serial port at 115200 baud and sends only
the read-only `info`, `help`, and `version` commands. It recognizes:

- Bruce from its `Bruce v...` or Heltec device identity response.
- Marauder from its ESP32 Marauder banner, `Firmware: Marauder` information,
  or distinctive command-list header.
- GhostESP from its Revival version banner, command-category heading, or
  `ghost>` prompt.

The detector closes the serial port before enabling the matching controller, so
the selected module can claim the port normally. Empty, unrelated, or
conflicting output leaves the app in **Unknown** state. Detaching a
USB-identified board retains its verified screen and session. GhostESP
continues through an already-connected GhostNet link; otherwise the app shows
an offline/standalone state. Reattaching either board triggers identity
verification and reopens USB automatically.

Once a USB or local-device Wi-Fi session is opened, a low-priority Android
foreground service owns it independently of the visible screen. Switching apps,
locking the phone, or recreating the Activity therefore keeps the existing serial
descriptor and NetworkSpecifier request alive. The ongoing **Heltec device
session** notification returns to the controller; reopening the Activity attaches
to the live session instead of probing and reopening the device. Serial output
received while the screen is absent is buffered and delivered when it returns.

GhostESP can also be detected without USB. **Detect GhostNet** requests the
default local-only `GhostNet` / `GhostNet` network and verifies GhostESP
branding at `http://192.168.4.1`. Marauder detection remains USB-only.

## Requirements

- Android 10 (API 29) or newer.
- A phone with USB host support and a data-capable USB-C OTG connection for USB
  detection and control.
- GhostESP's GhostNet access point for network operation.
- The customized Marauder firmware for its USB controller.

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

## Hidden Bruce interface

Bruce USB/WebUI code is preserved in the project, but no Bruce tab or BruceNet
detection button is shown in version 0.8.0. If a Bruce USB signature is found,
the app identifies it and explains that its controls are temporarily hidden.

## Marauder interface

After Marauder is verified, the app automatically reopens its USB port. The
Reconnect control remains available for permission or cable recovery. The app provides:

- A live 115200-baud console with reliable page and live-follow controls.
- One continuous, cutout-safe page lets the title, firmware detection controls,
  tabs, and selected firmware interface scroll together.
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
- The complete source firmware command line with command-risk gating.
  Transmit/state-changing commands require typed `AUTHORIZE`; unknown commands
  require explicit review.

Marauder Bluetooth scanning is a firmware feature, not an app transport. The
current Marauder build still requires USB for Android control.

### Bruce phone-assisted Wi-Fi logging

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

The app ID is `com.unkl3errl.helteccontroller`, version `0.8.0`, code 21.
This preserves update continuity with the permanent Heltec Controller signing
identity. Release builds use the four `HELTEC_RELEASE_*` environment variables
documented in [`SIGNING.md`](SIGNING.md); without all four, Gradle deliberately
produces an unsigned release.
