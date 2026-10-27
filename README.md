# Android → Mac LAN Audio

Stream playback audio from an Android phone to a Mac (iMac / MacBook) over the local network.

- `android/` — sender app (Java, Android 10+, debug build). See [android/README.md](android/README.md).
- `receiver/` — macOS receiver (Swift): CLI receiver plus a native AppKit `.app`. See [receiver/README.md](receiver/README.md).
- `PROTOCOL.md` — wire protocol (LANPCM1/LANPCM2 audio on TCP 50555, LANCTRL1/LANCTRL2 control on TCP 50556).
- `docs/ux/` — design notes and wireframe for sender-side receiver discovery.

## Security

Audio, the pairing PIN and the paired-device token travel **unencrypted** on the LAN. Use only on a trusted private network. Never commit receiver runtime data (`status.json`, `paired-devices.json`, QR images).

## Quick start

Android sender (JDK 17, Android SDK; create `android/local.properties` with `sdk.dir=...`):

```sh
cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS receiver app (universal, ad-hoc signed):

```sh
./receiver/macos-app/build-app.sh
```

Receiver CLI and self-tests:

```sh
cd receiver
swiftc -O -parse-as-library -framework AVFoundation -framework AudioToolbox -framework Security \
  -framework CoreServices -framework CoreAudio -framework CoreImage -framework AppKit \
  Receiver.swift CLIMain.swift -o android-imac-audio-receiver
./android-imac-audio-receiver --test-pairing-store
./android-imac-audio-receiver --test-qr
```

`receiver/launchd/com.openbot.lanaudio.receiver.plist` is a LaunchAgent template; replace `YOUR_USER` with your macOS user name before installing it to `~/Library/LaunchAgents/`.

## Project status

**What it is:** Android sender app + macOS receiver that stream phone playback audio to a Mac over the LAN, with PIN/QR pairing, persistent paired devices, receiver discovery via Bonjour, and paired output selection/per-output volume. The Android client probes `LANCTRL2` with `list_outputs`; Bonjour does not advertise output-control capability.

**Verified (2026-10-28, macOS arm64, JDK 17, Android SDK 35):**

| Check | Command | Result |
|---|---|---|
| Android unit tests, debug build, lint | `cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL; 23 unit tests |
| Receiver compiles | `swiftc ... Receiver.swift CLIMain.swift` (see Quick start) | OK |
| Pairing store (persistence, 0600 file, revoke) | `./android-imac-audio-receiver --test-pairing-store` | PASS |
| Pairing QR generate/decode | `./android-imac-audio-receiver --test-qr` | PASS |
| LANCTRL2 output list/select and per-output volume (fake HAL) | `python3 receiver/tests/control_protocol_probe.py <receiver-binary>` | PASS; does not change live audio output |

**Not covered / limitations:**
- No encryption: audio, PIN and paired token are plaintext on the LAN. Trusted private networks only; the sender accepts private/link-local IPs only.
- Only a debug APK is built; no signed release and no CI. Install the APK yourself with `adb install`.
- End-to-end Android-to-Mac audio and `--test-volume` (writes the Mac's system volume) were not re-run for this release. Android may capture silence from protected/DRM apps.
- Per-app capture is copy-only: Android's public API gives this app no way to silence only the selected app on the phone while other apps stay audible (do not mute the media stream, it affects all apps).
- Switching the Mac's output during playback is implemented (engine restart with bounded retry and route verification) but unverified: it was only tested with a fake audio backend, never between two physical outputs.
- A reported crash on the user's phone shortly after sending starts could not be reproduced or diagnosed (phone unreachable over ADB, no logcat); the lifecycle fixes in this release are defensive and its root cause is unconfirmed. The Android UI was reviewed on an API 35 emulator only, not on a physical phone.
- Receiver runtime data (PIN status file, QR image, `paired-devices.json`) lives in `~/Library/Application Support/OpenBot/LAN Audio Receiver/` and is never part of this repository.
- No LICENSE file yet: all rights reserved until the owner adds one.
