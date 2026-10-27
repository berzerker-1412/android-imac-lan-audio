# Android → iMac LAN audio prototype

Scope: Android 10+ sends permitted system playback audio to a macOS 12.7.6 Intel iMac on the same LAN. No internet service or account.

## Wire protocol v1

- TCP port 50555, one sender at a time.
- The receiver generates a fresh six-digit pairing code on each launch. In an interactive terminal it may print the code locally; in background mode the code is kept in the receiver's private status file. The sender asks for the iMac IP and code unless the user scans the local QR described below.
- Sender sends UTF-8 `LANPCM1\n`, then the six-digit code and `\n`. Receiver responds `OK\n` or `ERR\n` before audio starts.
- Remaining bytes are raw interleaved signed 16-bit little-endian PCM, stereo, 48,000 Hz. Sender may write any chunk size. Receiver groups bytes into whole sample frames.
- Receiver uses a small bounded playback queue and drops late audio rather than allowing delay to grow without limit.
- Closing the TCP connection ends playback. No other control messages.

## Discovery

- While ready to accept a connection, the receiver advertises DNS-SD/Bonjour service `_openbot-lanaudio._tcp.` on the local network, port 50555, with TXT record `v=1`.
- The Android sender uses NSD to list visible receivers by service instance name and resolve the selected host/port. The manual IP field remains a fallback when multicast discovery is unavailable.
- Bonjour TXT advertises receiver version only; it does not advertise output-control capabilities. A paired Android client discovers those capabilities by probing `LANCTRL2` `list_outputs` and handles an explicit unsupported-command response separately from transient or response-size failures.
- Discovery does not change the PIN handshake or carry the PIN in TXT records.

## Pairing QR and remote volume extension

The v1 audio stream and Bonjour TXT `v=1` remain compatible.

- The receiver can show a QR code locally with this URI: `openbot-lanaudio://pair?v=1&host=<IPv4>&port=50555&pin=<six-digits>&name=<receiver-name>`. `name` is optional for legacy QRs. Encode query values normally. Do not advertise the PIN in Bonjour.
- Android scans the QR, validates the scheme/version, private or link-local IPv4 host, fixed port, and six-digit PIN, then fills the existing fields. Scanning a QR does not start screen/audio capture; the user still taps Start and approves Android's system prompt.
- A separate TCP control listener uses port 50556 on the same LAN interface. Client sends `LANCTRL1\n`, the current six-digit PIN plus `\n`, and receives `OK\n` or `ERR\n`. Close on failed authentication; limit retries.
- After `OK`, client sends one newline-terminated UTF-8 JSON object (maximum 4096 bytes), then receives one newline-terminated JSON response. Commands are `{"id":1,"cmd":"get_volume"}` and `{"id":2,"cmd":"set_volume","value":50}` where value is an integer 0–100 for the macOS default output device's **system** volume. Success returns `{"id":1,"ok":true,"volume":50}`; failure returns `{"id":1,"ok":false,"error":"..."}`. Server then closes the connection. Validate types and range; do not silently claim success on an output device that cannot expose/set system volume. Close each connection after one request/response; reject a failed PIN immediately and apply a bounded failed-PIN rate limit.
- Paired `LANCTRL2` adds default-output discovery/selection and per-output volume without changing the handshake or existing commands. `{"id":3,"cmd":"list_outputs"}` returns `{"id":3,"ok":true,"outputs":[{"uid":"<Core Audio UID>","name":"<display name>","is_default":true,"selectable":true,"volume_readable":true,"volume_settable":true,"volume":42}]}`. `volume` is omitted when unreadable; capability flags reflect the current device properties. Only audio outputs are listed.
- The paired Android client accepts response lines up to 4096 characters; the receiver bounds JSON responses to 4096 UTF-8 bytes. Output lists whose complete encoded response exceeds this bound return an explicit `ok:false` error. The receiver has no pagination; increasing the limit or adding pagination requires a coordinated contract change.
- `{"id":4,"cmd":"set_output","output_uid":"<Core Audio UID>"}` selects that currently available output as the **macOS system default** and returns `{"id":4,"ok":true,"output_uid":"<actual UID>","name":"<display name>"}`. This changes audio routing system-wide and may affect every app. Reject missing, stale, non-output, or non-selectable UIDs with `ok:false` and a human-readable `error` string.
- `get_volume` and `set_volume` retain their old behavior when `output_uid` is absent (use the current default output). Paired `LANCTRL2` may address a specific listed output with `"output_uid":"<Core Audio UID>"`; success includes both `output_uid` and integer `volume` (0–100). A missing/stale UID, unreadable volume, or non-settable volume returns `{"id":N,"ok":false,"error":"<human-readable reason>"}`. Legacy PIN-authenticated `LANCTRL1` keeps default-only behavior and cannot list or select outputs or target a UID.
- The receiver does not expose mute or Bluetooth commands. Pair/forget Bluetooth devices in macOS Settings. The JSON request/response framing, 4096-byte request limit, and one-request-per-connection behavior are unchanged.
- The PIN and control messages are plaintext on the trusted LAN. The QR contains the PIN and should be displayed only on the iMac on demand, not stored in a shared directory.

## Long-lived Android device pairing

The user can trust this Android device until explicitly unpaired. The six-digit
PIN remains a bootstrap code and changes on every receiver launch; neither app
stores it for later sessions. Existing v1 clients continue to work with the
current PIN while the paired-device protocol is added.

- To pair, Android opens TCP 50556 and sends these newline-terminated UTF-8
  fields, in order: `LANPAIR1`, current six-digit PIN, a stable random device
  ID (16–64 ASCII characters from `[A-Za-z0-9_-]`), display name (1–80 UTF-8
  bytes, no CR/LF), and a cryptographically random 32-byte token encoded as 64
  hexadecimal characters. The receiver replies `OK\n` only after atomically
  saving the new authorization; otherwise it replies `ERR\n` and closes. It
  accepts at most 32 devices and rejects duplicate IDs.
- The Android app encrypts the device ID, token, receiver host and display name
  with an Android Keystore AES-GCM key in app-private storage. Exclude this
  material from Android backup. Never log or display the token.
- The receiver stores device IDs, names and tokens in
  `~/Library/Application Support/OpenBot/LAN Audio Receiver/paired-devices.json`
  with directory mode 0700 and file mode 0600. Writes use a private temporary
  file followed by atomic replacement. Tokens are compared in constant time.
- Paired audio sends `LANPCM2\n`, device ID and token, each newline terminated.
  A paired connection is authorized only if both ID and token match. The audio
  payload remains the same PCM format as v1.
- Paired control sends `LANCTRL2\n`, device ID and token, then the existing
  newline-delimited JSON request/response. The `unpair` command is
  `{"id":1,"cmd":"unpair"}`. On success the receiver atomically removes that
  device and replies `{"id":1,"ok":true}` before closing. Android clears its
  encrypted local pairing only after this acknowledgment; if the receiver is
  unreachable or rejects the request, retain the local credential and offer
  retry.
- Invalid PIN/bootstrap attempts are limited to five per receiver launch;
  new PIN-based pairing/control remains locked until the receiver restarts.
  Already paired token-based audio/control remains available. Do not log PINs
  or tokens. Pairing credentials and audio remain plaintext in transit on the
  local network, so use only a trusted LAN. Explicit unpair revokes the
  receiver-side token as well as clearing the Android copy.

This is a LAN prototype: pairing and audio travel without encryption. Use only on a trusted local network. Android's AudioPlaybackCapture API can capture only playback that the source app permits; DRM or protected apps may yield silence. Show this limitation in the sender UI.

## Ownership

- `android/`: Mobile Dev owns sender app.
- `receiver/`: Desktop Dev owns macOS receiver.
- Both sides use this exact protocol. Protocol changes were coordinated with Claude before locking this extension.
