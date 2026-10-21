# Third-party software

unbraid is built on other people's work. This file lists what the app includes or downloads and under which
licence. The complete licence texts, including every Go module linked into the sing-box library, ship inside the
app in `app/src/main/assets/notices.txt`. `tools/notices.sh` regenerates that file after a libbox build.

## Included in the app

| Component | Used for | Licence |
|---|---|---|
| [sing-box](https://github.com/SagerNet/sing-box) 1.14.3, with the patches in `tools/` | The VPN engine (libbox) | GPL-3.0-or-later |
| [wireguard-go](https://github.com/sagernet/wireguard-go) (SagerNet fork) | WireGuard inside libbox | MIT, Copyright WireGuard LLC |
| Go standard library and runtime | Part of libbox | BSD-3-Clause |
| [sagernet/gomobile](https://github.com/sagernet/gomobile) | Java bindings for libbox | BSD-3-Clause |
| Go modules linked into libbox | Protocols, crypto and networking in sing-box | Each module's own licence, listed with its full text in `notices.txt` |
| AndroidX, Jetpack Compose, Material 3, Material Symbols | User interface | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Language runtime | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) core 3.5.3 | Reads QR codes from a photo or picture at sign-in | Apache-2.0 |
| ThreeTenBP | Date and time code inside the Kotlin standard library | BSD-3-Clause |
| [lzokay](https://github.com/jackoalan/lzokay) | The LZO code in go-lzo derives from it | MIT |
| [uTLS](https://github.com/metacubex/utls) | TLS fingerprints | BSD-3-Clause, with parts under LGPL-3.0 (with a static-linking exception) and MIT |
| [Big Shoulders Display](https://github.com/xotypeco/big_shoulders) | Font | SIL Open Font License 1.1 |
| [Onest](https://github.com/googlefonts/onest) | Font | SIL Open Font License 1.1 |
| [Martian Mono](https://github.com/evilmartians/mono) | Font | SIL Open Font License 1.1 |
| [gluetun](https://github.com/qdm12/gluetun) | ExpressVPN OpenVPN profile and provider notes | MIT |
| [PIA manual-connections](https://github.com/pia-foss/manual-connections) | Sign-in and region steps for Private Internet Access | MIT |
| PIA certificate authority (`pia_ca_rsa_4096.crt`) | Pinning TLS to PIA's WireGuard servers | MIT, from PIA manual-connections |

## Google Play build only

| Component | Used for | Licence |
|---|---|---|
| Google Play Billing Library | The optional tip jar | Android Software Development Kit License |

The APK published on GitHub and meant for F-Droid doesn't contain it.

## Downloaded while the app runs

| Source | Used for | Licence |
|---|---|---|
| [gluetun-servers](https://github.com/qdm12/gluetun-servers) | Server lists for providers without a public API | MIT |
| Each provider's own API | Server lists, sign-in and keys | The provider's terms |

## Build tools

Gradle, the Android Gradle Plugin and the Kotlin compiler are under the Apache License 2.0. The Gradle wrapper
checks the Gradle distribution it downloads against a pinned SHA-256 checksum. The unit tests use JUnit 4.13.2
(EPL-1.0) and Hamcrest Core 1.3 (BSD-3-Clause), which are not part of the app.

If you think something is missing or credited wrongly, please open an issue.
