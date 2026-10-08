# T3 / Android 4.4 Validation

## Target and Provenance

- User-reported hardware: T3 head unit, Android 4.4; iPhone 15 Pro, iOS 26.6.
- Local release: `0.2.18-t3.1`, version code `38`, package `com.shihab.diplay`.
- Upstream base: DiPlay `v0.2.14`, commit `7887bb7bf2b52258e663a2a4ea1332382ed80ad8`, already merged into this fork.
- Android installation floor: API 19. Native APK includes ARMv7 for the 32-bit T3.
- Local verification device: Android 4.4.4 / API 19 x86 emulator, 1024 x 600. It is not the T3 kernel or hardware.

## Compatibility Work

- Media3 pinned to 1.4.1, without a manifest override of incompatible library minimum SDKs.
- KitKat AudioTrack, legacy audio focus and media-button handling, plus guarded newer codec/UI APIs.
- KitKat station Wi-Fi and hotspot interface discovery without Android 5/6 network APIs.
- USB configuration values and alternate settings read from raw descriptors on the authorized connection; do not assume configuration 1 or alternate 0.
- Native USB alternate selection uses the real descriptor value; USBMUX and NCM retain their shared authorized connection.
- USB re-enumeration actively polls when old vendor firmware omits ATTACHED, with bounded failure reporting.
- KitKat VPN uses a native blocking TUN fd. Only IPv6 link-local traffic is routed; API 19 cannot scope a VPN by application.
- Release builds require production signing and standalone runtime authentication assets; no debug-signing fallback.
- Standalone builds validate key/certificate consistency and repeated signatures before packaging.

## Automated Checks

`T3LegacyRuntimeTest` runs on the actual API 19 emulator, rather than an unsupported Robolectric API 19 sandbox. Its checks cover:

1. Opening each settings category.
2. Opening and closing manual-hotspot, Wi-Fi Direct and USB connection screens. This is an entry-point test, not a transport connection test.
3. Loading the native TUN fd helper.
4. Preserving USB configuration and alternate-setting values through KitKat's hidden descriptor constructors.
5. Starting AudioTrack and observing a successful PCM write with legacy audio focus.
6. Starting the local AirPlay listener, answering `/info`, recovering from an occupied port and restarting it.
7. X25519, Ed25519 and ChaCha20-Poly1305 round trips on Dalvik.
8. Loading the bundled local accessory identity and signing a challenge. This proves local key consistency only.
9. Encoding a synthetic H.264 stream with the platform encoder, feeding CarPlay-format access units to the real decoder, observing a Surface frame and releasing the decoder.

## Authentication Input Repair

The previous local authentication input failed PKCS#8 parsing on both desktop Java and
API 19. Changing the Android crypto provider alone did not fix it. The original
`DiPlay-v0.2.17-legacy-release.apk` is preserved; its rejected input was extracted
into a private temporary directory for a negative preflight test.

The replacement is the experimental identity from the existing downloaded upstream
`DiPlay-0.2.14.apk`. Its whole-APK SHA-256 is
`62b31f79db32bc7c85013ae830460b697a5952fad571ed0030b97341dde0b2e3`, matching
[the official release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.14).
Its key/certificate pair passed two independent loads and six locally verified
challenge signatures with changed-challenge rejection. No new identity was generated.
Runtime private inputs remain ignored by Git. An iPhone's trust decision is not
established by these local checks.

The negative test against the previous APK's nonempty but malformed key failed at
`verifyStandaloneAuthentication` with an EC key-parse error, as intended. The valid
upstream pair passes the same task. A file-existence check alone is insufficient.

Execution results and signed APK checks are recorded after the final build. Do not interpret the test list itself as a passed result.

## Physical Acceptance Still Required

No T3 or iPhone is connected to this build host. A complete successful CarPlay session on this exact combination has **not** been demonstrated. Existing normal Bluetooth pairing, hotspot networking, USB storage installation and iPhone trust prompts do not establish RFCOMM, USBMUX/NCM, accessory authentication or projection compatibility.

All three modes need parked-vehicle acceptance: fresh/upgrade installation, iPhone authorization, first picture, touch, music, navigation voice, Siri microphone and reconnect after unplug/restart. A simulated USB descriptor or a local `/info` response cannot replace these checks.

This independent receiver is not an Apple-certified accessory. The experimental local identity may be rejected by an iPhone. It is not possible to promise one-time connection success without the physical acceptance run.
