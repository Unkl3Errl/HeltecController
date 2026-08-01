# Android release signing identity

Direct-distribution releases of `com.unkl3errl.helteccontroller` use the
existing permanent identity:

| Field | Value |
| --- | --- |
| Alias | `heltec-controller` |
| Store format | PKCS#12 |
| Certificate subject | `CN=HeltecController, O=Unkl3Errl` |
| Certificate SHA-256 | `15:17:B9:22:56:7D:55:7E:9E:71:B5:4A:14:1C:48:56:27:FD:27:50:CF:BC:F8:D1:40:75:C6:D0:AF:37:7C:A4` |

Keep the keystore and passwords outside Git. The build reads them only from
`HELTEC_RELEASE_STORE_FILE`, `HELTEC_RELEASE_STORE_PASSWORD`,
`HELTEC_RELEASE_KEY_ALIAS`, and `HELTEC_RELEASE_KEY_PASSWORD`. A partial
configuration fails immediately; an absent configuration creates an unsigned
release APK.
