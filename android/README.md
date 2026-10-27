# LAN Audio Sender (Android)

Debug build for Android 10+; follows the sibling `../PROTOCOL.md` without protocol changes.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the iMac or MacBook receiver on the same trusted LAN. The sender keeps scanning for `_openbot-lanaudio._tcp.` receivers advertising TXT `v=1`; discovered receivers show whether each is paired, and tapping one selects it. Enter the current six-digit code and choose **Pair this Mac** to add that receiver while preserving other pairings. You may keep multiple Macs paired and select which one to use. Enter a private IP manually if discovery is unavailable. The phone stores each random device ID and token encrypted with Android Keystore; Android backup is disabled for the app and the credential preference is excluded from backup/transfer. Pairing does not start audio. After pairing, tap **Start sending** and approve Android's playback-capture prompt. **Stop** closes the TCP connection. Apps may block capture and produce silence.

**Unpair this phone** revokes access only on the selected Mac and clears only that encrypted phone record after the Mac confirms. If that Mac is unreachable, its saved pairing remains so you can retry. Paired receiver records remain available for selection while discovery continues.

Bonjour discovery searches for the exact saved service instance name of every paired receiver. If a receiver's private IP changes, the sender encrypts and saves its new address while preserving that receiver's device ID/token. The selected receiver shows found, not found, or address-save failure; use **Refresh selected Mac** to retry. Discovery can report a receiver found, but the receiver must be reachable to pair or unpair it.

Tap **Scan iMac QR** to scan the receiver's local pairing QR; this fills the IP and PIN only and never starts capture. Google Play services provides the scanner surface, so the app does not request direct camera permission. While sending, **iMac speaker volume** reads and controls the Mac output over the separate authenticated TCP control channel (50556); it is not the Android phone volume.

The app can restrict playback capture to one selected launchable app by Android UID. This copies that app's eligible playback; it cannot make the selected app silent on the phone. Android source-app usage/capture policy and protected or DRM playback can prevent capture. Changing the selected app requires stopping and starting capture again with fresh MediaProjection approval.

Audio and pairing code are unencrypted. Use only a trusted LAN. The app accepts private/link-local IPs only; no hostname or public address.

## Build

```sh
./gradlew assembleDebug lintDebug
```
