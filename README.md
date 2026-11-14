<p align="center">
  <img src=".github/banner.png" alt="unbraid: every app, its own exit. Per-app VPN routing for Android, free and open source." width="760">
</p>

<p align="center">
  <a href="https://github.com/quiet-crafts/unbraid/releases/latest"><img src="https://img.shields.io/github/v/release/quiet-crafts/unbraid?label=download&color=FFA66B" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-13%2B-8EE0A8" alt="Android 13 or later">
  <a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0-7CC4FF" alt="GPL-3.0 licence"></a>
  <a href="https://unbraid.app"><img src="https://img.shields.io/badge/website-unbraid.app-C9A7FF" alt="Website"></a>
  <a href="https://github.com/quiet-crafts/unbraid-mac"><img src="https://img.shields.io/badge/also%20on-macOS-8EE0A8?logo=apple&logoColor=white" alt="Also on macOS"></a>
</p>

unbraid is a split tunneling VPN client for Android that lets every app take its own exit. Your banking app can skip the VPN
while a streaming app leaves from the US, a second app from Italy and everything else from the Netherlands, all
at the same time. If you use Tailscale, your home network stays reachable too.

<p align="center">
  <a href="https://github.com/quiet-crafts/unbraid/releases/latest"><b>Download the APK</b></a>
  &nbsp;·&nbsp; <a href="https://github.com/quiet-crafts/unbraid-mac">unbraid for Mac</a>
  &nbsp;·&nbsp; <a href="https://unbraid.app">Website</a>
  &nbsp;·&nbsp; <a href="https://unbraid.app/privacy">Privacy policy</a>
  &nbsp;·&nbsp; <a href="https://github.com/quiet-crafts/unbraid/issues">Report a bug</a>
</p>

<table align="center">
  <tr>
    <td align="center" width="25%"><img src=".github/screenshots/routes.png" alt="Routes screen: a default exit in the Netherlands, with Chrome and YouTube in the US, Gmail in Sweden, Drive in Japan and Photos set to Direct" width="200"></td>
    <td align="center" width="25%"><img src=".github/screenshots/apps.png" alt="Apps screen: every installed app with the exit it uses" width="200"></td>
    <td align="center" width="25%"><img src=".github/screenshots/providers.png" alt="Choosing a location for a new route, with the list of signed-in providers open" width="200"></td>
    <td align="center" width="25%"><img src=".github/screenshots/stats.png" alt="Stats screen: data used per provider, live speed, connected time and speed tests" width="200"></td>
  </tr>
  <tr>
    <td align="center"><sub>Each app gets its own exit</sub></td>
    <td align="center"><sub>See every app's route at a glance</sub></td>
    <td align="center"><sub>Mix providers and countries</sub></td>
    <td align="center"><sub>Usage, speed and connected time</sub></td>
  </tr>
</table>

There is also [unbraid for Mac](https://github.com/quiet-crafts/unbraid-mac), with the same idea and the same
providers. It needs macOS 26 or later.

## Highlights

- Give any app its own exit country or server, let it follow the default route, or let it skip the VPN with
  Direct. Several exits stay connected at once.
- Use the VPN accounts you already have. One route can go through Mullvad while another uses NordVPN.
- Reach your home network through Tailscale or Headscale, or send chosen apps out through a Tailscale exit node.
- A low-load server is picked for you when the provider publishes load, or you can pin one server yourself.
- Ad and tracker blocking per route with the provider's own blocking DNS, where it has one.
- Saved profiles, a home-screen widget, a quick-settings tile and a pause that reconnects by itself.
- Back up your setup to a passphrase-protected file and restore it on this phone or a new one.
- Stats on the phone: data per provider, connected time and speed tests across several exits at once.
- No ads, no unbraid account and no analytics of our own. Free software under the GPL.

## What it works with

You bring the VPN. unbraid has no servers of its own and works with the accounts and servers you already have.

You can sign in with NordVPN (NordLynx, OpenVPN, Double VPN and Onion over VPN), Mullvad, Private Internet Access,
IVPN or ExpressVPN. For Proton VPN (Free included) and Surfshark you add your WireGuard key once, and for
Windscribe and AirVPN you paste one WireGuard config from their website. After that you choose from the servers
your plan includes. You can also import your own WireGuard or OpenVPN config, paste a Shadowsocks
key, or join a Tailscale tailnet (a self-hosted Headscale works as well).

Routes can mix providers. One app can use Mullvad in Sweden while another uses NordVPN in Japan.

## Getting started

1. Download the APK from the [releases page](https://github.com/quiet-crafts/unbraid/releases/latest) and open it
   on your phone to install it. Android may ask you to allow installs from your browser or file manager first.
2. Turn off any other VPN app, including your provider's own app, and its "Always-on VPN" setting. Android only
   lets one VPN app run at a time.
3. Open unbraid, accept the terms and choose your VPN. You can sign in to a provider, import a WireGuard or
   OpenVPN file, paste a Shadowsocks key, or use Tailscale on its own.
4. On the Routes tab, tap the default route, choose Change location and pick a country. This is where every app
   goes unless you say otherwise. If you imported a file, its server is already set. Then tap the power button to
   connect and allow the VPN when Android asks. With Tailscale on its own, connect first and then tap Sign in to
   Tailscale.
5. To send some apps somewhere else, tap +, then Route, and choose another country or server. Open the Apps tab
   and tap Show my apps (the list stays on your phone). Then tap an app and pick its route. Pick Direct for apps
   that should not use the VPN at all, like a banking app.

Each release lists the APK's SHA-256 checksum and the fingerprint of the signing certificate.
[unbraid.app/download](https://unbraid.app/download/) explains how to check them before you install.

## Good to know

unbraid is a VPN app, not a VPN service. It has no servers, so you need an account with a supported provider
(the free Proton VPN plan works) or a server of your own.

Because Android runs only one VPN app at a time, unbraid takes the place of your provider's app while it is on.
If you connect another VPN app, Android stops unbraid. If you allow its notifications, unbraid tells you it is
off.

Your provider's limits still apply. Providers with a connection limit count each route in use as a connection,
while Mullvad counts unbraid as one device. The default route stays connected while unbraid is on. Other routes
connect as soon as an app is assigned to them, even if that app is idle.

The app needs Android 13 or later on a 64-bit ARM phone. It is not on Google Play yet. To update, download the
new APK from the releases page and install it over the old one; your settings stay. The GitHub version can also
check for a newer version about once a day, but only after you switch that on. It asks unbraid.app for a small file
and identifies itself only as "unbraid".

## Questions

### How is this different from the split tunneling in my VPN app?

In many VPN apps, split tunneling means you can pick apps that skip the VPN, while every other app leaves from the
same place. unbraid lets you choose an exit for each app, and those exits can come from different providers.

### Can I use it without a VPN subscription?

Yes. The free Proton VPN plan works, and so does a server of your own. You can import a WireGuard or OpenVPN file,
paste a Shadowsocks key, or use only Tailscale, for example with an exit node at home.

### Can I reach my home network while I use a VPN?

Yes. With Tailscale, or with your router's WireGuard config added as a home tunnel, apps can reach devices at home
and still use their VPN exit for everything else.

### Does it have a kill switch?

It works with Android's own "Always-on VPN" and "Block connections without VPN" settings, which you can open from
the app's settings. With blocking on, apps cannot go online without unbraid, Direct apps included. Apps you set
to Direct still use your normal connection while unbraid is connected. unbraid's own requests can still go online
(see Privacy). Apps in a work profile are outside this VPN. If unbraid can't open its saved settings, it keeps apps
offline until you retry or start fresh, and an app whose chosen route no longer exists is blocked rather than sent
out over your normal connection.

### Does it get around my provider's device limit?

No. Providers with a connection limit count each route in use against it. Mullvad counts unbraid as one device.

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
`ConnectivityManager.getConnectionOwnerUid`. When an app hands a connection to another app or system service, such as
for a download, a browser tab or a push message, that connection follows the other app or service's route. Ping has no owning app, so it follows the default route (when Direct is the default and other apps use VPN routes, internet ping is blocked instead). DNS is answered with fake addresses and looked up again on the exit
the app really uses, so a site sees the country you picked for that app. Apps you set to Direct are left out of the
VPN by Android itself, unless you turn on "Block connections without VPN" or set the DNS filter to cover Direct
traffic. Then they go through unbraid and are sent straight out from inside the app. Apps that only follow a
Direct default route always go through unbraid. Only the default route and the routes your apps use are connected.

For NordVPN, unbraid picks a server from the provider's low-load recommendations when it connects, unless you
pinned one. Server lists
come from each provider's public API where there is one, and otherwise from
[gluetun-servers](https://github.com/qdm12/gluetun-servers).

## Build

You need JDK 17 or newer, Android SDK 37, NDK 28 and Go 1.26 (Go fetches the exact toolchain itself through
`GOTOOLCHAIN`).

```bash
tools/build-libbox.sh                 # builds app/libs/libbox.aar from sing-box v1.14.4 with the patches in tools/
./gradlew :app:assembleFossRelease    # the APK published here and meant for F-Droid, without Google Play Billing
./gradlew :app:bundlePlayRelease      # the Google Play bundle, which adds an optional tip jar through Play Billing
./gradlew :app:testFossDebugUnitTest
```

The build script keeps its work files in `~/.cache/unbraid-libbox`. Set `WORK` to use another folder. The patches
in `tools/` stop libbox from writing the running configuration, which holds private keys, to disk, seal the
Tailscale node state with an Android Keystore key, refuse files sent through Taildrop, give an OpenVPN tunnel a
resolver inside it when its server pushes none, let the app's connection checks look up their servers through
the tunnel being checked, and release every network part a tunnel built when it stops or fails to start. A release build is signed only if `.secrets/signing.properties`
exists, so a plain checkout builds an unsigned APK.

## Privacy

We add no advertising or analytics of our own. The Play version includes Google's Billing library for optional tips.
You don't need an unbraid account. Your provider credentials are encrypted with
an Android Keystore key and are only sent to the service they belong to. The app's own requests (provider sign-ins, server lists, the optional
update check, and the connectivity check when every tunnel is down) use your phone's normal connection, not the VPN,
so those services see your own IP address. The list of installed apps is read on the
phone so you can assign routes, and it never leaves the phone. "Copy a report" in the app's settings only copies text to the clipboard: the phone
model, Android version, app version, which providers and countries you use, and counts. It holds no keys, accounts,
addresses or app names. App data is excluded from Android's own backups. A backup file you save in Settings is locked with your passphrase
([how](SECURITY.md#backup-files)) and stays wherever you save it. Your own servers' keys and passwords go in only if
you choose, and provider sign-ins never do. The privacy
policy, with every service the app talks to, is at [unbraid.app/privacy](https://unbraid.app/privacy).

To report a security problem, see [SECURITY.md](SECURITY.md).

## Support the project

unbraid is free and has no paid features. If it is useful to you, see
[unbraid.app/donate](https://unbraid.app/donate/) for ways to support it. A donation doesn't unlock anything.

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
