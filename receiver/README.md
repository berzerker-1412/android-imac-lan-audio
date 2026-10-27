# Android → iMac LAN audio receiver

Small macOS receiver for `../PROTOCOL.md` (LANPCM1, TCP 50555).

## Build on the iMac

```sh
swiftc -O -parse-as-library -framework AVFoundation -framework AudioToolbox -framework Security -framework CoreServices \
  -framework CoreAudio -framework CoreImage -framework AppKit \
  Receiver.swift CLIMain.swift -o android-imac-audio-receiver
```

## Native desktop app

The AppKit UI and receiver engine are compiled into one universal `.app` so one
process owns the audio/control listeners. Build on macOS with:

```sh
./receiver/macos-app/build-app.sh
```

The result is `receiver/build/OpenBot LAN Audio.app` (x86_64 + arm64,
minimum macOS 12, ad-hoc signed). Open it from Finder. The window shows receiver
state, Mac name/IP, default output and readable volume, Start/Stop, and an
in-window pairing panel. The PIN and QR are created only when that panel opens
and are cleared when it closes. Closing the app stops a receiver it started.
When another process owns the port, the UI reports that and never terminates
that process. The UI app itself does not auto-start.

## Run, status and PIN

Running `./android-imac-audio-receiver` binds only to an active `en*` IPv4
interface, starts the audio engine, then advertises `_openbot-lanaudio._tcp.`
with TXT `v=1` and port 50555. A fresh six-digit PIN is generated for every
launch. In an interactive Terminal it is printed locally; in the background it
is stored only in a mode-0600 status file at
`~/Library/Application Support/OpenBot/LAN Audio Receiver/status.json`.
Run `~/bin/android-imac-audio-receiver --status` in Terminal to show current
status, address and PIN. PIN is omitted when stdout is redirected. The status
directory is mode 0700 and the file is removed on clean exit.

The receiver accepts one sender at a time. Its playback queue is capped at
12,000 stereo frames (250 ms); when full it discards the oldest frames to keep
latency bounded. Closing the sender connection ends playback; buffered audio
drains for up to 300 ms. The prototype has no encryption, so use it only on a
trusted local network.

## On-demand pairing QR and volume control

With the receiver running and a user logged into the iMac desktop, run
`~/bin/android-imac-audio-receiver --show-qr` in Terminal. This generates a
PNG with the current IPv4 address, port and PIN under the private app-support
directory, then opens it on the iMac. The PNG contains the PIN, has mode 0600,
is never put in a shared folder or Bonjour TXT record, and is removed when the
receiver exits or starts with a new PIN. The QR only fills Android's pairing
fields; the user still starts capture and approves Android's system prompt.
Run `--remove-qr` to remove the private QR manually.

The control listener on TCP 50556 accepts legacy `LANCTRL1` PIN-authenticated
volume requests and the long-lived paired-device protocol. On Android, **Pair
this iMac** registers a random per-device token with `LANPAIR1` using the
current PIN once. The receiver stores authorized devices in
`~/Library/Application Support/OpenBot/LAN Audio Receiver/paired-devices.json`
with directory mode 0700 and file mode 0600, using atomic replacement. Paired
audio and volume control use `LANPCM2` and `LANCTRL2`; the six-digit PIN is not
stored for later sessions and still rotates each receiver launch. The Android
app keeps its credential encrypted by Android Keystore. **Unpair** removes the
receiver-side authorization and clears Android's local credential after the
receiver confirms. The receiver allows up to 32 devices. Five failed
PIN/bootstrap attempts lock new PIN-based pairing/control until the receiver
restarts; paired token-based access stays available. Tokens are constant-time
compared and are never printed in receiver logs.

The LAN remains plaintext, including the initial PIN and paired token during
authentication. Use only on a trusted network. Output devices that do not
expose a readable/settable system volume return an explicit error.

The QR is not opened automatically when LaunchAgent starts. `--show-qr` must be
run in a logged-in desktop session; background stdout never contains the PIN.
`--test-qr` creates and verifies a disposable QR with dummy data; `--test-volume`
reads the current system-volume percentage and writes that same rounded value
back to verify the default output's CoreAudio control.

## Per-user auto-start on the iMac

The supplied LaunchAgent is `launchd/com.openbot.lanaudio.receiver.plist`; it is
installed at `~/Library/LaunchAgents/com.openbot.lanaudio.receiver.plist`. It
starts at user login and restarts after a process failure. Its stdout/stderr go
to `/dev/null`, so PINs are not written to logs. No system daemon or firewall
setting is changed.

Check status and PIN in Terminal:

```sh
~/bin/android-imac-audio-receiver --status
launchctl print gui/$(id -u)/com.openbot.lanaudio.receiver
```

Restart:

```sh
launchctl kickstart -k gui/$(id -u)/com.openbot.lanaudio.receiver
```

Disable auto-start and stop:

```sh
launchctl bootout gui/$(id -u) "$HOME/Library/LaunchAgents/com.openbot.lanaudio.receiver.plist"
mv "$HOME/Library/LaunchAgents/com.openbot.lanaudio.receiver.plist" \
  "$HOME/Library/LaunchAgents/com.openbot.lanaudio.receiver.plist.disabled"
```

Re-enable by renaming the `.disabled` file back to `.plist`, then run:

```sh
launchctl bootstrap gui/$(id -u) "$HOME/Library/LaunchAgents/com.openbot.lanaudio.receiver.plist"
```

## Synthetic protocol test

`tests/integration_probe.py` temporarily starts a receiver, reads its private
status file without printing the PIN, validates the generated QR image using
dummy test data, exercises PIN rejection and both volume commands (setting the
current rounded value back to itself), rejects an out-of-range volume, sends a
short synthetic 440 Hz tone, checks that AVAudioEngine consumed PCM, then stops
the receiver. When port 50555 is already occupied by a live receiver,
`tests/control_protocol_probe.py` tests JSON, legacy PIN auth, pairing,
paired-control auth, unpair, rate limiting, and CoreAudio using a temporary
loopback-only listener on 50556. `--test-pairing-store` verifies credentials
survive store reload, use owner-only permissions, and can be revoked. Neither
test writes audio files or logs test credentials. For an Android end-to-end test, the
sender must discover the Bonjour service and provide a permitted audio source.
Android playback capture may yield silence for protected or DRM content.
