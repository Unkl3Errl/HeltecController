# Security Policy

## Supported versions

Security fixes are made against the current release of the Android controller.
Before reporting a problem, reproduce it with the newest APK and the signed
firmware images published with that release whenever it is safe to do so.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Older releases | No |

## Reporting a vulnerability

Use this repository's **Report a vulnerability** form under the **Security**
tab. That private channel is appropriate for issues involving firmware-image
verification, Android storage permissions, USB or Bluetooth authorization,
credential exposure, command safeguards, or another weakness that should not
be public before a fix is available.

Include the app version, Android version and device model, connection type,
firmware and board version, a minimal reproduction, and the security impact.
Redact Wi-Fi passwords, API credentials, signing material, device identifiers,
and captured data. Do not attach private capture files unless a maintainer asks
for a safe sample through the private report.

For ordinary bugs and feature requests, use a public GitHub issue instead.
Problems that reproduce in an unmodified upstream firmware should be reported
to that upstream project; problems involving this app, its mobile compatibility
images, or Android-backed storage belong here.

Do not test a suspected vulnerability against networks, devices, accounts, or
radio spectrum without explicit authorization.
