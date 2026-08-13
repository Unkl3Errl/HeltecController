# Android release signing identity

Direct-distribution releases of `com.unkl3errl.helteccontroller` version 0.8.2
and later use this permanent identity:

| Field | Value |
| --- | --- |
| Alias | `helteccontroller` |
| Store format | PKCS#12 |
| Certificate subject | `CN=HeltecController v2, O=Unkl3Errl` |
| Certificate SHA-256 | `37:2B:AE:B4:32:9B:91:78:96:54:B3:72:CB:9F:B0:FE:95:47:39:F1:58:28:49:A3:59:23:47:A7:23:2F:DF:B8` |

The provisioned macOS machine stores the keystore at
`~/Library/Application Support/HeltecController/signing/heltec-controller-release-v2.p12`
and its password in Login Keychain under `HeltecController Release Signing v2`.
Run `./scripts/build-release-macos.sh` there to test and create the signed APK.

Version 0.8.1 used a different certificate whose private key was unavailable.
Android therefore required one uninstall/reinstall to establish this v2 update
line. Do not rotate or recreate the v2 key for future versions.

Keep the keystore and passwords outside Git. The build reads them only from
`HELTEC_RELEASE_STORE_FILE`, `HELTEC_RELEASE_STORE_PASSWORD`,
`HELTEC_RELEASE_KEY_ALIAS`, and `HELTEC_RELEASE_KEY_PASSWORD`. A partial
configuration fails immediately; an absent configuration creates an unsigned
release APK.
