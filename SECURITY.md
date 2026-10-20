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
unencrypted, or a way for another app on the phone to read or change unbraid's settings.

Problems in sing-box itself are best reported to the [sing-box project](https://github.com/SagerNet/sing-box). If
you're not sure where a problem belongs, send it here.

## Supported versions

Only the latest release gets security fixes.
