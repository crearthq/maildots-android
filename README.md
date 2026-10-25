# MailDots for Android

The Android app of [MailDots](https://maildots.com), a business e-mail service.
Kotlin Multiplatform + Compose; the Android target is the shipped one.

End-to-end encryption runs on the device. The crypto core is the same JavaScript code as
the webmail, published in [maildots-crypto](https://github.com/crearthq/maildots-crypto).
It ships as two local files (`composeApp/src/androidMain/assets/e2e/`) and runs in an
isolated WebView where every request except those two files is blocked. The build is
reproducible: the `integrity` hash in `core.html` must equal the hash that `pnpm build`
prints in maildots-crypto.

Push notifications: each install creates its own 256-bit key and gives it only to the
MailDots server, which seals sender, subject and unread count with AES-256-GCM. Firebase
(Google) carries only an opaque blob.

What MailDots can and cannot read, and the known limits:
[zero-access.md](https://github.com/crearthq/maildots-crypto/blob/main/docs/security/zero-access.md).

## Build

```bash
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:assembleDebug
```

Release builds need your own signing key (not in this repository).

## Reporting a vulnerability

See [SECURITY.md](SECURITY.md).

## License

Copyright © 2026 Creart Technology LLC.

Licensed under the [GNU Affero General Public License v3.0](LICENSE). The MailDots name
and logo are trademarks and are not covered by this license.
