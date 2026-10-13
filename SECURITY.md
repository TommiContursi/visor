# Security policy

## Supported versions

Only the latest release gets security fixes. Builds from `main` get them as soon as they land.

## Report a vulnerability

Do not open a public issue for a security problem.

Report it privately: on this repository, open the **Security** tab and select **Report a vulnerability**. Only you and the maintainer can read the report.

Include:

- What an attacker can do, and what they need first: an app on the phone, a nearby device or physical access.
- The Visor version, the phone and Android version, and the glasses' firmware.
- The steps to reproduce it, or a proof of concept.

Remove serial numbers, Bluetooth addresses, tokens and API keys from logs before you attach them. A `wire.log` holds all of them.

The maintainer answers as soon as possible and agrees a disclosure date with you. Please wait for a fix or for that date before you publish.

## Scope

In scope, because Visor's code decides them:

- How Visor stores the glasses' owner key, the Meta session and the assistant's API keys on the phone.
- How it handles what arrives from the glasses over Bluetooth and Wi-Fi Direct.
- Anything that lets another app, or a nearby device, control the glasses or read their data through Visor.
- The release build and its signing (`.github/workflows/release.yml`).

Out of scope:

- Problems in the glasses' firmware, in Meta's apps or in Meta's servers. Report those to [Meta's bug bounty program](https://bugbounty.meta.com/).
- Attacks that need a rooted phone, or a phone that is already unlocked in the attacker's hands.

## Check a release

Release APKs are signed with the certificate below. Check it before you install an APK from anywhere other than this repository's releases:

```
apksigner verify --print-certs visor-<version>.apk
```

```
CN=Visor
SHA-256: F6:01:C7:FE:63:EF:04:E0:7F:66:8A:EC:BA:5A:2B:9C:23:90:C4:C0:22:B5:2B:5F:B0:F6:D3:04:22:7C:B9:C7
```
