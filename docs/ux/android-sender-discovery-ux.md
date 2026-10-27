# UX handoff: Android sender Bonjour receiver discovery

**Version:** v1 · UI-only handoff for Mobile Dev  
**App:** LAN Audio Sender, native Android Activity  
**Wireframe:** `android-sender-discovery-wireframe.svg`  
**Scope:** Discover compatible receivers and select one; preserve manual private IP, six-digit PIN, existing Start/Stop/status and capture flow. Do not change receiver or protocol.

## Current UI reviewed (read-only)

`MainActivity` is a programmatic native Android vertical form: title and intro, manual iMac IP, six-digit pairing code, playback capture limitation text, status, Start sending and Stop. Intro already communicates trusted LAN and unencrypted audio/PIN. The protocol advertises Bonjour `_openbot-lanaudio._tcp.` with TXT `v=1`, TCP port `50555`; PIN handshake remains unchanged. Manual host accepts private/link-local LAN addresses only.

## Recommended screen order

Keep one screen and add a “Receivers on this network” section between intro/security note and the existing manual IP/PIN fields. User may select a discovered receiver or continue with manual IP. Selecting a row only fills the address and confirms fixed port `50555`; it does not start capture or send the PIN.

1. **Title:** `Send audio to iMac` (current title).
2. **Short network note:** `Connect only on a trusted Wi-Fi/LAN. Audio and PIN are not encrypted.` Keep visible near the top; do not hide in an overflow menu.
3. **Discovery section:** header `Receivers on this network`; status/list panel below.
4. **Manual fallback:** persistent `Or enter IP manually` divider, IP field and fixed `Port 50555` suffix/chip. The port is displayed but not editable, because the current service/protocol uses 50555.
5. **PIN field:** keep the six-digit field; numeric keypad, max six digits, mask value by default with reveal toggle if already supported. Never prefill from TXT/discovery.
6. **Capture note:** `Android asks for microphone permission for playback capture; the app does not record microphone audio. Some apps, including DRM/protected apps, may block capture and send silence.`
7. **Connection status:** preserve the existing status label and service state updates.
8. **Controls:** preserve full-width `Start sending` and `Stop`; Start enabled only when valid private/link-local IP and exactly six PIN digits are present. Stop remains available for active/connecting sessions and keeps current stop behavior.

Use a vertical `ScrollView` so the discovery list, both fields, capture note and controls remain reachable on small screens and with large font scale. On a typical portrait phone, keep primary controls visible near the bottom when possible; do not compress text to force everything into one viewport.

## Discovery filter and row behavior

- Discover Bonjour/DNS-SD type `_openbot-lanaudio._tcp.` only.
- Show a receiver only when its TXT record includes `v=1` and its advertised port is exactly `50555`. Ignore incompatible service versions/ports without showing a misleading selectable row.
- Receiver row title is the service instance/device name. After resolving, show private LAN IP and port: `192.168.1.42 · 50555`.
- Before host resolution completes, show `กำลังเชื่อมข้อมูล…` (`Resolving…`) as a row substate and disable row selection. Never substitute a guessed IP or hostname into the manual field.
- Tapping an available row highlights it, fills the manual IP field with its resolved LAN IP, and confirms the fixed port chip. Keep the PIN field untouched. Show a brief inline confirmation: `เลือก {device name} · {IP}:50555`.
- Tapping another receiver replaces the selected address. Editing the IP manually clears the selected receiver highlight; discovery can continue in the background without overwriting user edits.
- A receiver disappearing after selection must not silently replace its IP. Keep the selected address as entered; show an inline `Receiver no longer visible. Check the address or choose another receiver.` hint if a later discovery update confirms it is gone.
- A single compatible receiver can still be selected with one tap. Do not auto-fill or auto-start: user explicitly chooses endpoint and still enters PIN.

## Discovery states and exact copy

| State | Display | User action |
|---|---|---|
| Searching | Spinner + `Searching for receivers…` and `Keep this device and iMac on the same trusted Wi-Fi/LAN.` | Discovery continues; manual IP remains usable |
| Resolving | Row shows name + small progress indicator + `Resolving…`; row is not selectable yet | Wait; other available rows remain selectable |
| Available | Name, resolved private IP, `Port 50555`, status `Available`; row selectable with selected outline/check | Tap to fill IP and select receiver |
| Empty | `No compatible receiver found.` `Check that the receiver is running on the same Wi-Fi/LAN, or enter its private IP below.` | `Scan again`; manual IP remains visible |
| Discovery error | `Couldn’t search for receivers.` `Bonjour discovery may be blocked on this network.` | `Try again`; manual IP remains visible |
| Per-device resolve error | Keep device name, status `Couldn’t resolve`; do not expose malformed host | `Try again` per row or `Scan again`; user may use manual IP |
| Start validation error | Inline under field: `Enter a private or link-local IP address.` or `Enter the 6-digit pairing code.` | Focus first invalid field; do not clear valid input |
| Connecting / streaming / stopped | Keep existing status labels and service broadcasts | Preserve Start/Stop behavior |

Discovery must not block manual connection. If NSD/multicast is unavailable, keep the screen usable and let manual entry work. Do not add a separate onboarding gate.

## Security and capture messaging

- Place a compact warning card near discovery: `Trusted network only · Audio and PIN are sent without encryption.` Use warning icon and text; color is supplemental.
- Keep Android permission explanation near Start/capture note: microphone permission is required by Android playback capture but the app does not record microphone audio; Android capture consent remains per session.
- State the limit plainly: `Some apps, including DRM/protected audio, block capture and may send silence.` Avoid implying discovery or PIN fixes source app restrictions.
- Never send or display PIN in discovery TXT metadata. The existing six-digit PIN handshake and TCP stream are unchanged.

## Visual and native component spec

- Preserve existing native Android Activity and light Material-like style: background `#F8F9FC`, near-black title/text, native `EditText`/`Button` controls. Avoid introducing a new theme or Flutter UI.
- Content inset: 24dp left/right; top 24dp (current padding is 28dp); vertical section spacing 16dp; card internal padding 16dp; card radius 14–16dp.
- Title: 24sp bold (current). Body: 15sp; helper/status: 14–16sp; never reduce existing intro text below 14sp.
- Discovery row min height 72dp, target ≥48×48dp; 12dp horizontal inner padding; selected state uses outline/check plus label `Selected`, not color alone.
- IP and PIN fields retain current 54dp height. Fixed port chip aligns to IP field and has minimum 44dp height.
- Start/Stop retain current 56dp height and full width. Show Start disabled visually and semantically when endpoint/PIN invalid; Stop enabled only if current sender state allows stopping.
- On 320dp width, text wraps and view scrolls; never clip receiver names or warnings. Device names may ellipsize after one line, with full name in accessibility label.
- Respect system font scale and display size; when keyboard opens, scroll focused field and Start controls into view.

## Accessibility and motion

- Focus order: title → security note → discovery heading/status → receiver rows in list order → Scan again if present → manual IP → fixed port text → PIN → capture/DRM note → connection status → Start → Stop.
- TalkBack row label: `{device name}, {availability state}, {resolved IP}, port 50555`; selected row announces `selected`.
- Buttons have text labels; icon-only retry/search gets content description. Status changes are announced politely, without repeatedly announcing each search tick.
- Searching indicator may rotate; with system animation/reduced motion preference, use static icon and text. Resolve state should not move list rows or steal focus.

## Acceptance checklist

- [ ] Results only include `_openbot-lanaudio._tcp.` services with TXT `v=1` and port `50555`.
- [ ] Searching, resolving, available, empty, discovery error and resolve error are distinct and readable.
- [ ] Tapping available receiver fills IP and fixed port 50555; PIN remains user-entered; connection does not start automatically.
- [ ] Manual private/link-local IP entry remains visible and works when discovery fails/is unavailable.
- [ ] Existing six-digit PIN validation, Android capture permission flow, Start/Stop and service status are preserved.
- [ ] Trusted LAN, unencrypted audio/PIN and app/DRM capture limits remain visible.
- [ ] No PIN enters Bonjour TXT, no receiver/protocol changes, and no changes to receiver app.
