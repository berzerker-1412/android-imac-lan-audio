# LAN Audio Sender — Android UX redesign

**Status:** implementation-ready design handoff · **Scope:** Android sender UI only. This document does not change the receiver or wire protocol. Do not edit `android/` as part of this design task.

## Goal and review basis

Make the core task — choose a Mac, choose an audio source, then start or stop sending — clear without making the user work through the current long form. Preserve Bonjour discovery, QR pairing, manual private IP fallback, six-digit bootstrap code, encrypted saved pairing, MediaProjection consent, single-app capture, and iMac volume control.

Reviewed read-only: `android/app/src/main/java/com/openbot/lanaudio/MainActivity.java`, `AudioSenderService.java`, `android/README.md`, `PROTOCOL.md`, and the existing `android/UX-SCAN.md` / `android/UX-REMOTE.md` handoffs. Current styling is a light native Android Activity (`#F8F9FC`, 24dp page inset, white rounded cards, native controls). App UI copy is currently English, so exact strings below are English; keep the copy in Android string resources and localize to Thai as a complete set if the app adds Thai localization.

## Prioritized findings

1. **Major — primary task is buried.** Discovery, QR, manual address, PIN, pairing, source selection, warnings, connection status, volume, and Start/Stop are all in one scrolling form. The user cannot quickly tell which receiver will be used or what step is next.
2. **Major — receiver and credential states are mixed.** An unpaired Mac needs a current code; a paired Mac uses a stored token and hides the code; a scanned QR fills address and code but does not itself establish a saved pairing. Present these as explicit setup states.
3. **Major — streaming controls compete with setup.** Volume appears within the same long form only after `Sending`; it should become the session’s main content, with receiver/source identity and Stop always easy to reach.
4. **Moderate — safety explanations repeat.** The trusted-LAN warning and capture/DRM note appear separately from the moment they matter. Keep one persistent compact security note and one source-specific explanation, with details adjacent to QR/pairing and Start respectively.
5. **Moderate — state copy can be more actionable.** Replace generic service errors with a short event, a likely fix, and a retry action where possible. Never expose credentials or imply that a listed app is guaranteed to produce audio.

## Navigation model

Use three native screens, with a small method sheet for adding a receiver. Back returns to the prior screen and preserves entered values.

1. **Connect** — selected Mac, source, connection readiness, and primary Start action. This is the app’s landing screen.
2. **Choose Mac** — tabs/segmented actions for `Nearby`, `Scan QR`, and `Enter IP`. Selecting a nearby receiver returns to Connect. Selecting an unpaired receiver moves to its pairing details.
3. **Sending** — active receiver/source, confirmed iMac volume, and prominent Stop. Back returns to Connect without stopping; Android notification remains the system-level session affordance.

`Add a Mac` opens a bottom sheet with `Find on this network`, `Scan QR`, and `Enter IP manually`. Existing saved Macs are visible on Connect and in Nearby; switching the active Mac is disabled during a session. `Unpair` remains a separate, confirmed action and clearly says it revokes the saved access on that Mac.

## Screen specs

### 1. Connect (landing)

Order:

1. App bar: `LAN Audio` and settings-free overflow only for non-core information (About/privacy); no hidden required actions.
2. **Receiver card:** Mac name, paired state, last known/found state, and `Change`. With no receiver: `Choose a Mac` primary card and `Add a Mac` action. Paired selection uses stored authorization and does not ask for a PIN.
3. **Audio source card:** `All compatible apps` / `One app`. Default remains All compatible apps. In One app mode show icon, app name, and `Change`; if missing or stale, show an inline error and block Start.
4. **Capture note:** `Android copies eligible playback audio. It does not mute audio on this phone. Some apps, including DRM-protected apps, may send silence.` Keep visible before Start; expand only if useful, not required.
5. **Security note:** `Trusted network only. Audio and pairing details are not encrypted.` Persistent, compact, with a warning icon and text (not color alone).
6. Sticky bottom CTA: `Start sending` (disabled until receiver and source are valid). Show one concise readiness line above it, e.g. `Ready · Mac mini · All compatible apps`.

### 2. Choose Mac

- Header `Choose a Mac`; selected saved Mac has a check mark and `Paired` label.
- `Nearby` list rows: name, secondary text `Available · 192.168.1.42`, or `Searching…` / `Couldn’t connect`; paired label is separate. Tap on Available selects it; a paired device can be selected while offline using its saved address, but show `Not found on this network`.
- Search discovery automatically on entry, with `Scan again` action. Do not clear already known saved devices while scanning. Discovery failure never blocks QR or manual entry.
- `Scan QR` opens Google Play services scanner only after user action. On valid QR, fill the receiver details and continue. QR does not start sending. If QR name matches a saved Mac, select its saved pairing; otherwise offer `Pair this Mac` or `Use once`.
- `Enter IP manually` reveals private/link-local IP field and fixed `Port 50555`. Never offer hostname/public IP. Keep validation inline and do not erase valid fields on error.
- Rows and actions have ≥48dp targets; list rows ≥72dp. Long names wrap to two lines; full name remains available to TalkBack.

### 3. First-time pairing details

Show when selecting an unpaired Bonjour receiver or scanning a new receiver QR:

- Receiver identity and resolved private IP/port.
- For Bonjour/manual: masked 6-digit `Pairing code` field; for QR, fill the code but do not announce or log it. QR may be used only once or saved by pairing.
- Primary action `Pair this Mac` securely saves this phone’s authorization for next time. Secondary action `Use once` keeps current session on the six-digit code without saving the pairing, if the current implementation supports starting with the bootstrap PIN. The code must remain valid at Start; if expired, show `This code may have expired. Get a fresh code from the Mac and try again.`
- Pairing result: `Paired with {Mac name}. You can connect without entering a code next time.` Return to Connect with this Mac selected.
- Pair failure retains receiver and code inputs. Say `Couldn’t pair with {Mac name}. Check that it is on the same trusted network and that the code is current.` Actions `Try again` and `Edit details`.

### 4. Choose app

- Use a full-height native picker screen instead of a cramped modal. Header `Choose one app`; search field; alphabetical rows with 40dp icon, app label, and selected check. Minimum row height 56dp. Selection immediately saves and returns to Connect; Cancel returns without changing the prior choice.
- No selection mode means `All compatible apps`; switching back is explicit on Connect and clears the saved single-app selection.
- Empty query result: `No apps match “{query}”.` with `Clear search`. Catalog failure: `Couldn’t load apps.` with `Try again` and `Use all compatible apps`.
- Explain once below search: `Only eligible playback from the selected app is copied. The app may block capture.`
- While sending, changing mode or app prompts: `Stop sending to change audio source?` Actions `Keep sending` / `Stop and choose`. Stop and release the current capture first; apply the new selection only after Stopped. User must tap Start and accept fresh Android capture consent again.

### 5. Sending

- App bar with receiver name and connection indicator. Show `Sending to {Mac name}` and source (`All compatible apps` or app icon + name).
- **iMac output volume** card directly below status. Slider 0–100%, confirmed value, `Mute`/`Unmute`; pending state `Updating…`; only show values confirmed by receiver. On read/write error, restore last confirmed value and offer `Try again`. Do not confuse with Android phone volume.
- Prominent full-width `Stop sending` fixed near bottom, ≥56dp. Stop remains available while connecting, sending, or stopping; button label changes to `Stopping…` and disables duplicate taps while stop is pending.
- Back does not stop capture; notification persists. If user reopens the app, restore Sending screen from service state.
- If single-app capture reports sustained silence: inline, non-blocking banner `No audio detected from {app}. Check that it’s playing; some apps block playback capture.` Keep Stop and volume usable; do not infer app mute/DRM cause.

### 6. iMac audio output (progressive disclosure)

Keep the Sending screen focused on the active session. Its compact **iMac output** card shows the active macOS output device name, volume, and a `Manage output` disclosure. Expand it or open a dedicated page only when the user asks; Bluetooth administration must never be mixed into the Android source-app picker. Core Audio supports listing and selecting the Mac's default output, and changing it affects the whole Mac.

**Manage output page order**

1. **Current output** — device name and type (`Built-in`, `Headphones`, `Bluetooth`, etc. only if supplied by the receiver), plus route state such as `Connected`, `Connecting`, or `Unavailable`. If the current device cannot be identified, say `Current output device unavailable` instead of guessing.
2. **Choose output** — list only devices the receiver confirms are currently selectable. Mark the active route with check + `In use`. Selecting another output must first show a confirmation dialog: `Change the Mac's default output? This changes sound output for the whole Mac, including other apps.` Actions `Cancel` / `Change output`. After confirmation, show `Switching…`, then update only after macOS confirms. Do not show Android phone outputs here.
3. **iMac output volume** — retain the 0–100% slider and mute control, explicitly scoped to the current Mac output. The displayed value and whether it can be changed depend on the active output. During a route switch, disable volume edits until the new route is confirmed. If the new device has no controllable system volume, show `Volume control isn’t available for this output` and keep the route state visible.
4. **Bluetooth on the Mac** — do not show remote `Pair`, `Connect`, `Disconnect`, or `Forget` actions in Android. Pairing may require a PIN or confirmation on the Mac, and there is no documented unpair API. Use concise guidance: `To pair or remove a Bluetooth device, use System Settings > Bluetooth on your Mac.` Do not imply the Android app can open or control that Mac settings page. A Bluetooth output may appear in the output list after it is paired and made available on the Mac.

**Capability boundary.** Desktop Dev confirmed Core Audio can list and select the Mac default output; this is system-wide. Output-specific volume support varies. Bluetooth pairing may require PIN/confirmation on the Mac, and no documented unpair API was found, so Bluetooth administration stays in macOS System Settings. The receiver/protocol still must confirm which commands are implemented and their acknowledgment/error behavior before Android exposes output switching or volume controls. Do not imply controls exist just because the OS API is capable; render them only when receiver support is available.

**Capability-dependent states and copy**

| State | Display / action |
|---|---|
| Loading output devices | `Loading iMac audio outputs…`; keep active route shown if known; prevent duplicate switch requests. |
| No alternative output | `No other available output devices.` Keep current route and volume controls. |
| System-wide output confirmation | Before switching: `Change the Mac's default output? This changes sound output for the whole Mac, including other apps.` Actions `Cancel` / `Change output`. |
| Route switch pending/confirmed/failed | Pending: `Switching to {device}…`; disable route/volume edits until acknowledgment. Success: `Output changed to {device}.` update only with receiver confirmation. Failure: `Couldn’t change the Mac’s output. The current output is still {device}.` Action `Try again`; retain confirmed volume. |
| Output permission/approval required | Show only the verified macOS requirement and recovery action when Desktop Dev confirms them. Do not invent a Bluetooth permission name or imply Android permission grants macOS access. |
| Bluetooth management | No remote discovery/pair/connect/disconnect/forget UI. Show `To pair or remove a Bluetooth device, use System Settings > Bluetooth on your Mac.` A Bluetooth output becomes selectable only after it is available from the Mac receiver. |
| Volume unsupported on selected route | `Volume control isn’t available for {device}. Adjust it on the device or in macOS sound settings.` Use only after this fallback is confirmed appropriate for that route. |

**Confirmed by Desktop Dev**

- Core Audio supports listing/selecting the default output; this changes output for the whole Mac.
- Volume control depends on the active output device.
- Bluetooth pairing may require a PIN/confirmation on the Mac. No documented unpair API was found.

**Still needed before implementation:** Desktop Dev must identify supported receiver/protocol commands, acknowledgments, timeouts, errors, per-output volume behavior, and any OS approval/permission requirement. Keep output controls hidden until receiver support is implemented and confirmed. The Android app must not offer remote Bluetooth pairing or unpairing.

## Flow and state transitions

```text
Connect (no Mac) → Choose Mac → {Nearby | Scan QR | Enter IP}
  → unpaired: Pairing details → Pair this Mac → Connect (ready)
  → saved Mac: select → Connect (ready)
Connect (ready) → Start sending → Android permission/MediaProjection system prompt
  → Connecting → Sending → Stop sending → Stopped → Connect
```

- Start requires valid private/link-local IP, either saved paired credentials or a current six-digit PIN, and a valid selected app if One app mode is active.
- Start always requires explicit user tap. Android’s capture consent remains system-owned and is requested for every capture session. A denied/cancelled consent returns to Connect with `Capture permission wasn’t granted. Tap Start to try again.`
- For an unpaired endpoint, failed authentication does not clear the code: `The Mac didn’t accept this code. Check it or scan a fresh QR.`
- Connecting timeout/reachability: `Couldn’t connect to {Mac name}. Check that both devices are on the same trusted network.` Actions `Try again`, `Choose another Mac`.
- If selected saved Mac is not found: keep it selectable with last saved IP and show `Mac not found on this network. Check Wi-Fi or refresh.` Do not silently select another receiver.
- If saved pairing store cannot be read/saved, explain that saved pairing is unavailable; allow manual one-time code if the receiver is reachable. Never claim a pairing was saved if persistence failed.
- During QR scanning: show scanner guidance `Point at the pairing QR on your Mac`; unsupported QR: `This QR code isn’t supported. Scan a current LAN Audio QR or enter details manually.` Preserve existing receiver selection on invalid QR.
- Unpair requires confirmation: `This revokes this phone’s saved access on {Mac name}. You’ll need the current code to pair again.` If actively sending, confirm `Stop and unpair`; only report success after receiver confirms revocation. If Mac is unreachable, retain saved credential and offer retry.

## Visual tokens and responsive behavior

- Keep native Android controls and current light palette: page `#F8F9FC`, card white, text `#1C1E22`, border `#DADCE5`, selected outline `#285FB4` (retain current selected blue if codebase uses `#2869B4`; use one token consistently). Do not introduce a visual redesign that requires a new framework.
- Page inset 20dp (24dp acceptable on wide phones); card inset 16dp; 16dp between sections; card radius 16dp; 1dp border. Compact app bar 56dp.
- Typography: screen title 24sp bold; section title 18sp; body 16sp; helper/status 14sp minimum. Respect font scaling; allow scroll and multiline wrapping rather than shrinking.
- Input fields 56dp high; primary CTA 56dp; secondary actions ≥48dp; slider track touch area ≥48dp; list rows ≥72dp. Bottom CTA includes navigation-bar inset.
- Width ≤360dp and large text: stack IP + port vertically if needed; buttons wrap/stack; no horizontal scrolling. Landscape/tablet may center content at max width 560dp. Keyboard automatically scrolls the focused field and next action into view.
- System reduced-motion preference: no essential pulsing/animated state; use static icon plus text. Do not reorder discovery rows while focused.

## Accessibility

- TalkBack order follows visual order; screen changes announce their title. State updates use polite live regions and announce only meaningful transitions, not discovery chatter.
- Receiver row label: `{Mac name}, {Available/Searching/Not found}, {Paired/Not paired}, {IP if known}, selected/not selected`.
- Mode choices use radio semantics; app rows announce app name and selected state. Keep selection conveyed by check plus text/semantics, not blue alone.
- Slider label `iMac output volume, {n} percent`; mute action labels change between `Mute iMac` and `Unmute iMac`. Announce confirmed changes politely, not every drag sample.
- Maintain WCAG AA contrast for text and controls; error states include an icon/label and corrective action. Do not read PIN digits aloud; mask by default.

## Acceptance checklist for implementation

- [ ] Connect screen shows the active Mac and audio source at a glance; one primary action starts a session.
- [ ] Nearby, QR, and manual IP routes remain available when discovery fails; manual accepts private/link-local IP only and keeps port fixed at 50555.
- [ ] Paired Mac skips PIN; unpaired flow distinguishes `Pair this Mac` from one-time use and never claims saved state before secure store success.
- [ ] QR fills/chooses the receiver and never starts capture or pairing by itself.
- [ ] Start requires explicit tap and Android capture consent; failed/cancelled consent returns to a clear retry state.
- [ ] All compatible apps remains default; one-app picker, stale app, empty/error, silence, and source change while sending are covered.
- [ ] Sending screen prioritizes receiver/source, receiver-confirmed iMac volume, and Stop; Android notification remains available.
- [ ] Stop, unpair, and switching receiver/source have explicit safe state transitions; credentials are not displayed in clear text or logged.
- [ ] All touch targets, font scale, TalkBack labels/order, contrast, reduced motion, small width, landscape, and keyboard behavior meet the specs above.

## Wireframe

See [`redesign-wireframe.svg`](./redesign-wireframe.svg) for low-fidelity Connect, Choose Mac, Sending, and capability-gated output management layouts.
