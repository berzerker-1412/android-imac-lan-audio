# UX spec: Scan for LAN audio receivers

**Scope:** Android sender UI only. Keep the existing capture protocol, receiver, six-digit PIN handshake, Start/Stop actions and status updates unchanged. The iMac receiver is expected to launch at login; the sender discovers it when it advertises Bonjour. If it is not advertising yet, show searching/empty and let the user retry.

## Current screen

`MainActivity` currently shows title and LAN/security/capture introduction, manual iMac IP, six-digit code, DRM limitation, status, Start sending and Stop. Add discovery without removing these existing controls.

## Layout and behavior

Keep one native Android screen, vertically scrollable, in this order:

1. Existing title and concise safety note: **Use only on a trusted Wi-Fi/LAN. Audio and PIN are not encrypted.**
2. Section **Receivers on this network** with scan status and compatible receiver rows.
3. Persistent divider **Or enter IP manually**, existing private/link-local IP field, and a read-only port label **50555**. No editable port field.
4. Existing six-digit PIN field. Selecting a receiver never fills, reveals, or sends the PIN.
5. Existing permission/capture note and DRM limitation.
6. Existing connection status, **Start sending**, and **Stop** controls.

Discovery filters strictly to service `_openbot-lanaudio._tcp.`, TXT `v=1`, port `50555`. A row shows the device/service name; after resolution, show `192.168.x.x · Port 50555`. Tap an available row to highlight it and fill the IP plus fixed port. Do not auto-start; the user still enters the PIN and taps Start. If user edits the IP, clear the row selection and do not let later discovery overwrite the manual value.

| State | UI copy / behavior |
|---|---|
| Searching | Progress indicator + `Searching for receivers…`; keep manual IP usable. |
| Resolving | Show device name + `Resolving…`; disable that row until the LAN IP is resolved. |
| Available | Show name, resolved private IP, port 50555, and `Available`; tap selects/fills fields. |
| Empty | `No compatible receiver found.` `Check that the iMac receiver is running and both devices are on the same trusted Wi-Fi/LAN.` Buttons `Scan again`; keep manual fallback visible. |
| Discovery error | `Couldn’t search for receivers.` `Network discovery may be unavailable.` `Try again`; preserve manual IP and PIN. |
| Local-network permission denied (only on OS/target SDKs that require it) | Explain that LAN discovery and direct IP connection need local-network access; actions `Try again` and `Open settings`. Keep the IP/PIN visible but disable Start while the OS blocks LAN access. |
| Resolve error | Keep name, show `Couldn’t resolve`; offer `Try again` and do not fill a guessed address. |
| Connecting/streaming/stopped | Preserve the service's existing status text and Start/Stop behavior. |

## Permissions and validation

- Keep current Android microphone permission explanation: Android requires it for playback capture; the app does not record microphone audio. Request it only after user taps Start with a valid LAN IP and six-digit PIN, as in the existing flow; then request Android capture consent for each session.
- Keep the limitation visible: **Some apps, including DRM/protected audio, may block capture and send silence.**
- Project currently targets SDK 35. Do not add a new runtime permission prompt solely for current discovery without verifying platform behavior. Before increasing target SDK, review Android's local-network permission changes: Android 16 has opt-in protections, and Android 17 enforces local-network permission for apps targeting SDK 37+. If permission is required and denied, explain that both discovery and a direct LAN connection may be blocked; provide a settings/retry action rather than promising manual IP will bypass it. [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- Android NSD reference for discovery and service resolution: [Use Network Service Discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd).
- Start validation stays field-specific: invalid/missing address → `Enter a private or link-local IP address.`; PIN not exactly six digits → `Enter the 6-digit pairing code.` Preserve valid input and focus the first invalid field.

## Native layout/accessibility

- Keep existing light native Activity styling (`#F8F9FC`, native `EditText` and `Button`); use a scroll container for small screens and font scaling.
- Existing content inset is 24dp horizontal. Use 16dp section spacing, discovery rows ≥72dp high, touch targets ≥48dp, existing IP/PIN fields 54dp, Start/Stop 56dp. Text: title 24sp, body 15sp, helper 14sp minimum.
- Focus order: safety note → discovery heading/status → receiver rows → retry → manual IP/port → PIN → capture/DRM note → status → Start → Stop.
- TalkBack row label: `{device name}, {state}, {IP if resolved}, port 50555`. Announce state changes politely, not every scan tick. Selected row has a check/outline and an accessibility state, not color alone.

## Acceptance

- [ ] Only compatible Bonjour receivers (`_openbot-lanaudio._tcp.`, `v=1`, `50555`) appear.
- [ ] Searching, resolving, available, empty, discovery error and resolve error are distinct.
- [ ] Tapping an available row fills IP and fixed port; PIN stays manual; no automatic Start.
- [ ] Manual private/link-local IP fallback remains visible.
- [ ] Existing PIN, permission, capture, service status, Start and Stop behavior remains intact.
- [ ] Trusted-LAN/unencrypted and DRM warnings stay visible.
- [ ] No receiver, protocol, or audio-stream changes are part of this UI task.
