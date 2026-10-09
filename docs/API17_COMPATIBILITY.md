# DiPlay v0.2.20 Android 4.2 / API 17 compatibility

## Baseline and scope

This branch is based directly on upstream `v0.2.20`
(`9a21d4cf797b99cd781ed981c51ad4c8f828fa13`).  It does not use an older
no-authentication test branch as its functional base.

The compatibility target is Android 4.2 / API 17 on a 32-bit ARM vehicle
head unit.  The app is built with `minSdk = 17`, an Android 17 NDK platform,
and only the `armeabi-v7a` ABI.  Modern-only paths must remain protected by
runtime SDK checks; this change does not remove USB, Bluetooth, hotspot, or
Wi-Fi Direct code.

## Authentication boundary

The application retains the original local MFi authentication implementation:

- `DiPlayBootstrap` installs the two required assets into app-private storage.
- `LocalMfiAuthenticationClient` validates the certificate/private-key pair
  and challenge signatures before the local MFi target is used.
- Native interface library `libxcertplay_i2c.so` remains part of the ARMv7
  package.

No identity or private-key material is tracked in this repository.  Upstream
v0.2.20 release metadata was audited for the expected asset paths, but its
credential bytes are neither extracted nor copied here.

An authenticated Standalone build requires an authorized caller to provide:

```text
DIPLAY_AUTH_ASSETS_DIR/
  offline-mfi/identity.pk8
  offline-mfi/certificate.p7b
```

`assembleStandaloneDebug` validates those inputs before packaging.  GitHub
Actions accepts the same inputs only via protected repository secrets and
keeps its Standalone output as a short-retention workflow artifact; it must
not be published as a public release asset.

`assembleDebug` is intentionally a source-only API17 installation test.  It
does **not** contain MFi identity files and must not be described as a full
Standalone CarPlay build.

## Automated evidence

The `android42-api17.yml` workflow checks the test APK for:

- package minSdk 17 and targetSdk 36;
- `armeabi-v7a` as the only native ABI;
- v1 (JAR) signing and ZIP alignment;
- the retained native authentication/hotspot libraries; and
- absence of accidental authentication material from the public test package.

When authorized MFi material is supplied, the separate Standalone job also
checks that exactly the two supplied assets are packaged, without printing or
exporting their contents.

## Still requires vehicle validation

An APK build and static inspection cannot prove iPhone CarPlay negotiation.
The CS55 vehicle must still validate Android 4.2 installation/launch, USB
permission handling, Bluetooth pairing, hotspot or Wi-Fi Direct group
creation, and wired/wireless CarPlay connection using legitimately provisioned
authentication material.
