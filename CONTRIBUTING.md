# Contributing

Contributions that improve reliability, accessibility, device compatibility,
or authorized defensive testing are welcome. Keep each change focused and
describe the Android device, board, firmware, and transport used for testing.

## Before opening a pull request

1. Open an issue first for a substantial feature or protocol change so its
   behavior and compatibility can be agreed on.
2. Build with JDK 17 and Android SDK 35.
3. Run the same local gate used by continuous integration:

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
   ```

4. Test connection lifecycle changes with screen-off/background operation and
   cable or radio interruption where applicable.
5. Add or update tests for parsing, catalog verification, transport routing,
   storage acknowledgments, and other deterministic behavior.

Release signing credentials are not required for a pull request. A normal
developer build must not contain production keys or locally captured data.

## Compatibility and safety requirements

- Accept firmware images only through the signed catalog and existing checksum,
  size, host, image-header, chip, and ROM-security checks.
- Never acknowledge a virtual-SD segment until Android has durably finalized it
  and verified its byte count and CRC-32.
- Keep consoles and storage isolated by physical device identity.
- Preserve explicit confirmation for flashing and potentially transmitting or
  state-changing commands.
- Keep passive detection read-only.
- Do not add features intended for unauthorized access, interception, jamming,
  credential theft, or destructive use.

## Pull request notes

Explain what changed, why it is needed, the commands run, and the hardware paths
tested. Include screenshots for visible UI changes, but remove device IDs,
network names, locations, capture contents, and other private information.

Report security-sensitive findings privately according to
[`SECURITY.md`](SECURITY.md), not in an issue or pull request.
