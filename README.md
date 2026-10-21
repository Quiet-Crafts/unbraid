# unbraid

unbraid is a split tunneling VPN client for Android that lets every app take its own exit. Your banking app can skip the VPN
while a streaming app leaves from the US, a second app from Italy and everything else from the Netherlands, all
at the same time. If you use Tailscale, your home network stays reachable too.

Project website: [unbraid.app](https://unbraid.app)

## What it works with

You bring the VPN. unbraid has no servers of its own and works with the accounts and servers you already have.

You can sign in with NordVPN (NordLynx, OpenVPN, Double VPN and Onion over VPN), Mullvad, Private Internet Access,
IVPN or ExpressVPN. For Proton VPN (Free included) and Surfshark you add your WireGuard key once, and for
Windscribe and AirVPN you paste one WireGuard config from their website. After that you choose from the servers
your plan includes. You can also import your own WireGuard or OpenVPN config, paste a Shadowsocks
key, or join a Tailscale tailnet (a self-hosted Headscale works as well).

Routes can mix providers. One app can use Mullvad in Sweden while another uses NordVPN in Japan.

## How it works

Android allows one VPN app at a time, so unbraid runs a single `VpnService` with one TUN interface and the
[sing-box](https://github.com/SagerNet/sing-box) engine behind it.

```
apps -> TUN -> sing-box -> rules: DNS, home, LAN, the app's route, default
                           |- one WireGuard or OpenVPN exit per location
                           |- Tailscale node (home)
                           '- direct
```

Each TCP and UDP connection is routed by the app that owns it, which Android reports through
`ConnectivityManager.getConnectionOwnerUid`. Ping has no owning app, so it follows the default route. DNS is answered with fake addresses and looked up again on the exit
the app really uses, so a site sees the country you picked for that app. Apps set to Direct are left out of the
VPN by Android itself. If you turn on "Block connections without VPN", they are sent out directly from inside the
app instead. Only the default route and the routes your apps use are connected.

For NordVPN, unbraid picks a server from the provider's low-load recommendations when it connects, unless you
pinned one. Server lists
come from each provider's public API where there is one, and otherwise from
[gluetun-servers](https://github.com/qdm12/gluetun-servers).

## Install

Download the APK from the [releases page](https://github.com/quiet-crafts/unbraid/releases/latest). It needs
Android 13 or later on a 64-bit ARM phone. Each release lists the APK's SHA-256 checksum and the fingerprint of
the signing certificate, so you can check the file before you install it.

Turn off any other VPN app first, including your provider's own app, and its "Always-on VPN" setting. Android
only lets one of them run.

## Build

You need JDK 17 or newer, Android SDK 37, NDK 28 and Go 1.25 (Go fetches the exact toolchain itself through
`GOTOOLCHAIN`).

```bash
tools/build-libbox.sh                 # builds app/libs/libbox.aar from sing-box v1.14.3 with the patches in tools/
./gradlew :app:assembleFossRelease    # the APK published here and meant for F-Droid, without Google Play Billing
./gradlew :app:bundlePlayRelease      # the Google Play bundle, which adds an optional tip jar through Play Billing
./gradlew :app:testFossDebugUnitTest
```

The build script keeps its work files in `~/.cache/unbraid-libbox`. Set `WORK` to use another folder. The two
patches stop libbox from writing the running configuration, which holds private keys, to disk, and seal the
Tailscale node state with an Android Keystore key. A release build is signed only if `.secrets/signing.properties`
exists, so a plain checkout builds an unsigned APK.

## Privacy

There are no ads or analytics, and you don't need an unbraid account. Your provider credentials are encrypted with
an Android Keystore key and are only sent to the service they belong to. The list of installed apps is read on the
phone so you can assign routes, and it never leaves the phone. App data is excluded from backups. The privacy
policy, with every service the app talks to, is at [unbraid.app/privacy](https://unbraid.app/privacy).

To report a security problem, see [SECURITY.md](SECURITY.md).

## Support the project

unbraid is free and has no paid features. If it is useful to you, you can donate at
[unbraid.app/donate](https://unbraid.app/donate/). A donation doesn't unlock anything.

Bug reports are welcome in the [issue tracker](https://github.com/quiet-crafts/unbraid/issues). The project
doesn't take pull requests. [CONTRIBUTING.md](CONTRIBUTING.md) explains why and how else you can help.

## Licence

unbraid is free software under the GNU General Public License, version 3 or (at your option) any later version.
See [LICENSE](LICENSE). sing-box, which unbraid is built on, is under the GPL as well.

unbraid includes or downloads work by other people under their own licences. [THIRD-PARTY.md](THIRD-PARTY.md)
lists them, and the full texts ship inside the app (`app/src/main/assets/notices.txt`, generated by
`tools/notices.sh` from the Go modules that are actually linked).

unbraid is an independent project and isn't affiliated with or endorsed by the VPN providers named here,
Tailscale or SagerNet. Product names belong to their owners.

## About this repository

unbraid is published by Quiet Crafts. Parts of the code and of the text in this repository were written with the
help of AI tools. The developer reviews and tests every change before it is released.
