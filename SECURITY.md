# Security

unbraid handles VPN credentials and decides where every connection goes, so security reports matter a lot here.

## Reporting a problem

Please don't open a public issue for a security problem. Email info@unbraid.app instead, with "Security" in the
subject line. Describe what you found, which version you tested (Settings, About & support) and, if you can, the
steps to reproduce it. You'll get a reply within a few days.

Please give a reasonable amount of time for a fix before you publish details. Fixed issues are mentioned in the
release notes.

## What counts

Anything that could expose a user's traffic, DNS lookups, credentials or keys. Some examples: an app reaching the
internet outside its chosen route, a leak while the VPN connects or reconnects, secrets written to disk or logs
unencrypted, a way for another app on the phone to read or change unbraid's settings, or a weakness in backup
files.

Problems in sing-box itself are best reported to the [sing-box project](https://github.com/SagerNet/sing-box). If
you're not sure where a problem belongs, send it here.

## Backup files

*Settings, Backup and restore* saves the setup to a file locked with a passphrase the user chooses. The file holds
routes, profiles, favourites, settings and own servers. Own servers' keys and passwords (including configs imported
from a VPN provider with the login inside them) and the home WireGuard peer go in only when the user turns on
*Include server keys and passwords*, which is off by default and can't be turned on when there is nothing for it to
include; a passphrase the app rates easy to guess is then refused. Without them, an own server keeps only its name and kind, plus the server address and public key for
WireGuard and Shadowsocks. After such a restore its routes stay blocked until its config is imported again, unless
the phone already has that server, which keeps its config. The file never holds accounts signed in through unbraid's
provider sign-in screens (their logins, tokens and device keys), Tailscale state or statistics.

- **Key:** scrypt ([RFC 7914](https://www.rfc-editor.org/rfc/rfc7914)) with N = 2^16, r = 8 and p = 2 and a random
  16-byte salt turns the passphrase (Unicode NFC, UTF-8) into 64 bytes: an AES key and an HMAC key. The app's scrypt
  uses a 64 MiB working array, which makes guessing passphrases costly.
- **Encryption:** AES-256-GCM with a random 12-byte nonce. The file's header is authenticated as additional data, so
  any change to the settings in it is caught.
- **Wrong passphrase or damaged file:** a SHA-256 checksum at the end catches accidental damage or a cut file before
  any key is made. A 16-byte HMAC-SHA256 check value checks the key made from the passphrase, so a wrong passphrase
  gets its own message. A deliberately changed file can be reported as a wrong passphrase; AES-GCM still refuses it.
- **Limits:** files are at most 4 MiB. The scrypt settings a file may ask for are capped at a 128 MiB working array
  and twice the default work. Opening a file needs some memory beyond that, and fails with a message if the phone
  has too little. The header carries a format version; an older app refuses a newer file instead of misreading it.
- **Where the code comes from:** scrypt was written for unbraid (`data/Scrypt.kt`) and is checked against the RFC's
  test vectors. AES-GCM, HMAC-SHA256 and SHA-256 come from Android's own cryptography; no cryptography library is
  bundled for backups. See [THIRD-PARTY.md](THIRD-PARTY.md).

Without the passphrase, the setup inside is unreadable. The file's size and its header (format, scrypt settings, salt,
nonce and check value) stay visible, and the suggested file name holds the date it was saved. The
passphrase is its weak point: anyone with the file can try to guess it, so a short or common one gives little
protection. The app rates the passphrase as it is typed, and refuses a weak one when keys are included. A lost
passphrase can't be recovered.

## Supported versions

Only the latest release gets security fixes.
