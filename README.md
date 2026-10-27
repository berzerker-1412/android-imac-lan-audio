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
