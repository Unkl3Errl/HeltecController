# Heltec Firmware Controller for Android

This is the single Android companion for the two independent Heltec WiFi LoRa
32 V4 firmware builds in this workspace:

- Bruce in [`Unkl3Errl/HeltecFirmware`](https://github.com/Unkl3Errl/HeltecFirmware)
- ESP32 Marauder in [`Unkl3Errl/ESP32Marauder`](https://github.com/Unkl3Errl/ESP32Marauder)

The app keeps both feature modules installed but locks both tabs until it
identifies the connected firmware. It does not merge the firmware images or
assume that the common Espressif USB ID identifies either project.

## Firmware detection

USB detection opens the attached CDC serial port at 115200 baud and sends only
the read-only `info` and `help` commands. It recognizes:

- Bruce from its `Bruce v...` or Heltec device identity response.
- Marauder from its ESP32 Marauder banner, `Firmware: Marauder` information,
  or distinctive command-list header.

The detector closes the serial port before enabling the matching controller, so
the selected module can claim the port normally. Empty, unrelated, or
conflicting output leaves the app in **Unknown** state. Detaching a
USB-identified board relocks both tabs.

Bruce can also be detected without USB. **Detect BruceNet** requests the default
local-only `BruceNet` / `brucenet` network and verifies the unauthenticated
Bruce WebUI login signature at `http://172.0.0.1`. This Marauder build has no
WebUI controller service, so Marauder detection is USB-only.

## Requirements

- Android 10 (API 29) or newer.
- A phone with USB host support and a data-capable USB-C OTG connection for USB
  detection and control.
- Bruce WebUI mode for BruceNet operation.
- The customized Marauder firmware for its USB controller.

## Bruce interface

After Bruce is verified, the app provides:

- Authenticated board, firmware, battery, memory, Wi-Fi, BLE, GPS, logger, and
  LoRa status through BruceNet.
- GPS monitor controls, GPS/BLE field logging, phone GPS assistance, file
  inventory, and Android document-picker export.
- Native rendering and navigation of Bruce's compiled vector display.
- LittleFS browsing, viewing/editing, creation, rename, delete, and download.
- A guarded 115200-baud USB CDC console with read-only shortcuts.
- SX1262 receive controls/history and firmware-constrained transmission with
  typed confirmation.
- The original WebUI in a network-bound embedded browser.

Default Bruce values are:

| Setting | Default |
| --- | --- |
| Wi-Fi SSID | `BruceNet` |
| Wi-Fi password | `brucenet` |
| WebUI URL | `http://172.0.0.1` |
| WebUI username | `admin` |
| WebUI password | `bruce` |

## Marauder interface

After Marauder is verified, tap **Connect** to reopen its USB port. The app
provides:

- A live 115200-baud console with reliable page and live-follow controls.
- Read-only/help/GPS shortcuts plus bounded Wi-Fi and BLE discovery workflows.
- Parsed AP and BLE result views with CSV export.
- Private, timestamped USB session history with secret redaction, view, rename,
  export, share, and delete actions.
- The complete source firmware command line with command-risk gating.
  Transmit/state-changing commands require typed `AUTHORIZE`; unknown commands
  require explicit review.

Marauder Bluetooth scanning is a firmware feature, not an app transport. The
current Marauder build still requires USB for Android control.

## Security and operating limits

- Detection never starts a scan, transmission, attack, or firmware update.
- WebUI and Wi-Fi credentials are held in memory and are not persisted.
- Phone GPS fixes go only to the selected Bruce device over its local link.
- Bruce uses cleartext HTTP because its isolated ESP32 WebUI is HTTP-only.
- Use radio and network tools only on systems, devices, and spectrum you own or
  are explicitly authorized to test.

## Build and signing

From this directory with Android SDK 35 installed:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app ID is `com.unkl3errl.helteccontroller`, version `0.5.0`, code 8.
This preserves update continuity with the permanent Heltec Controller signing
identity. Release builds use the four `HELTEC_RELEASE_*` environment variables
documented in [`SIGNING.md`](SIGNING.md); without all four, Gradle deliberately
produces an unsigned release.
