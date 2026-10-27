# UX handoff: QR pairing and iMac volume remote

**Scope:** Android sender UX only; no code, receiver, or wire protocol changes in this handoff. The open question about output-device switching is intentionally excluded.

## Current UI / technical boundary

`MainActivity` already lists Bonjour receivers, resolves the selected private IP, shows fixed port 50555, keeps manual IP and six-digit PIN fields, and preserves Start/Stop plus playback-capture permission flow. `AudioSenderService` currently captures eligible audio usages across apps; the capture configuration has no app UID filter. The iMac receiver generates a fresh six-digit PIN on launch. Current `PROTOCOL.md` has no QR payload and no remote-volume command; changing receiver volume from Android needs a receiver/control-channel feature agreed separately.

## Placement and flow

### Pair by QR

- Add a secondary button **Scan iMac QR** at the top of the receiver discovery card, beside/above **Scan again**. Keep Bonjour discovery and manual IP as alternatives.
- Tap opens a full-screen native camera scanner with a short privacy note: `Scan the pairing QR shown on your iMac. The QR contains the LAN address and pairing PIN.` Camera permission is requested only after this action; denial shows `Camera access is off` with `Open settings` and `Enter details manually`.
- A valid QR fills the existing IP field, fixed port chip, and six-digit PIN field, then returns to the sender form. Show selected receiver name and confirmation: `Details filled for {iMac name}. Press Start sending when ready.`
- **Never start sending automatically.** The user must tap the existing **Start sending** button and accept Android's playback-capture consent. Keep the existing microphone explanation (required by Android; the app does not record microphone audio).
- QR contract required from receiver/protocol owner: versioned local pairing payload containing receiver display name (optional), private LAN host, port `50555`, and current six-digit PIN. Do not put the PIN in Bonjour TXT. Reject unsupported versions, non-private/non-link-local addresses, wrong port, malformed host, or a PIN other than six digits. Do not invent an encoding silently; agree the exact QR format with the protocol owner before implementation.
- The QR is visible on the trusted local receiver display and contains an unencrypted PIN. Do not store the scanned image/payload after filling the fields; do not upload it or include it in logs. Existing warning remains: trusted LAN only; audio and PIN are unencrypted.

### Choose the audio source

- Add an **Audio source** card after destination/PIN and before the capture/DRM note. Use two radio options: **All compatible apps** (default; preserves current behavior) and **One app**. Avoid saying “all apps can be captured”; some apps opt out or use ineligible playback.
- In **One app** mode, show a `Choose app` row until selection. Then show the app launcher icon and label, for example `Only Spotify`, plus `Change`. Start is enabled when endpoint, PIN, and one-app selection are valid, or when All compatible apps is selected.
- `Choose app` opens a native single-select sheet/page with search, alphabetical app labels, 40–48dp app icons, selected radio/check, and rows at least 48dp high. Search filters visible labels. Selection only changes the filter and returns to the sender form; it does not request permissions or start capture.
- On Android 11+ (`targetSdk >= 30`), package visibility limits which installed apps queries can return. For a launcher-app picker, declare the narrow `ACTION_MAIN` + `CATEGORY_LAUNCHER` query in `<queries>` and use that result; do not request `QUERY_ALL_PACKAGES` by default. If the resulting list is empty, show `No launchable apps found.` with `Try again` and `Use all compatible apps`; do not claim that no capturable audio exists. If loading fails, show `Couldn’t load apps. Try again or use all compatible apps.` Keep network/pairing fields intact. See [Android package visibility](https://developer.android.com/training/package-visibility) and [declare package visibility needs](https://developer.android.com/training/package-visibility/declaring).
- Selected state visibly includes icon + name in the idle form and status, for example `Sending only Spotify to {iMac}`. In All compatible apps mode say `Sending audio from all compatible apps`.
- Start sequence in One app mode: valid receiver/address + six-digit PIN + selected app → user taps **Start sending** → existing microphone permission if needed → new Android MediaProjection consent → connect and capture only selected app UID. The QR scan and app selection themselves must never trigger permissions or start streaming.
- If the user selects a different app while streaming, show `Stop sending to change audio source?` with `Keep sending` / `Stop and choose`. Stop the stream first, release the current capture session, then open the picker. After selection, return to Stopped state; user must press **Start sending** and accept a fresh MediaProjection consent before the new source is captured. On Android 14+ consent is required for every MediaProjection capture session; never reuse an old token for the new app.
- If the user cancels app change, keep the current stream and app. If they pick the same app, dismiss without stopping.
- Playback capture copies eligible audio; it does not mute or reroute the source app on the phone. Android can filter capture by UID, but actual audio depends on eligible usage and the source app's capture policy/opt-out. A listed app may therefore produce silence. See [AudioPlaybackCaptureConfiguration.Builder.addMatchingUid](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration.Builder#addMatchingUid(int)), [capture eligibility](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration), and [MediaProjection consent per session](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent).

### Control iMac volume

- Show a compact section **iMac speaker volume** directly below connection status and above Start/Stop, only while connection state is `Sending`. Do not label it simply “Volume” (which could mean Android phone volume).
- Control: horizontal slider 0–100%, numeric value, and a `Mute`/`Unmute` button. Minimum interactive height 48dp. Slider changes update the displayed value immediately; show `Updating…` until receiver confirms the value. When disconnected/connecting/stopping, hide or disable the control with helper `Connect to adjust iMac volume`.
- On reconnect, display receiver-reported current volume; do not assume the Android phone media volume equals iMac output volume. Avoid persisting a stale slider value as the receiver's actual value.
- This section is **blocked on protocol support**: current TCP protocol streams PCM and has no control messages. Before enabling it, Desktop Dev/protocol owner must define a volume command, acknowledgment/current-volume response, and failure behavior. Until then, keep the section out of a production UI rather than showing a nonfunctional slider. No UI-only client-side gain change should be presented as changing iMac speaker/system volume.

## States and error copy

| State | UX |
|---|---|
| Scanner ready | Live viewfinder, corner guides, `Point at the QR on your iMac`, close/back. Do not capture before the user opens scanner. |
| Reading | Brief progress label `Reading QR…`; prevent duplicate scans. |
| Valid QR | Fill address/port/PIN; show confirmation; remain stopped until user taps Start and accepts capture consent. |
| Invalid/unsupported QR | `This QR is not a supported LAN Audio pairing code.` Actions `Scan again` / `Enter details manually`; preserve previously entered values. |
| Incomplete/expired PIN or bad address/port | `Pairing details are incomplete or invalid. Show a fresh QR on the iMac.` Do not partially overwrite form. |
| Camera permission denied | `Camera access is off. You can enter the iMac IP and PIN manually.` Actions `Open settings` / `Enter manually`. |
| Camera unavailable / scan timeout | `Couldn’t scan this QR. Improve the lighting or enter details manually.` Retry remains available. |
| Volume command pending | Keep thumb/value visible; label `Updating…`; avoid sending a burst for every drag sample (coalesce to final/short-debounced value). |
| Volume confirmed | Show receiver-confirmed percent; mute button reflects confirmed state. |
| Volume unsupported/disconnected | Hide control when unsupported; if an active command fails, restore last confirmed value and show `Couldn’t change iMac volume. Try again.` |
| App list empty/error | In One app mode: show `No launchable apps found.` or `Couldn’t load apps.` with retry and `Use all compatible apps`; retain connection fields. |
| No capturable audio | If capture reports sustained silence, show `No audio detected from {app}. Check that it is playing; some apps block playback capture.` Do not claim to know whether the app is paused, silent, or opted out; keep selected app visible and never imply it was muted. |

## Native layout and accessibility

- Keep the existing scrollable native Android screen, light background and native controls. Retain current discovery, manual IP, PIN, safety note, capture limitation, status, Start and Stop.
- QR entry target ≥48×48dp. Scanner view fills available area; close control ≥48dp; scanner guidance uses 16sp and does not obscure the code.
- QR error is announced to TalkBack; the scan surface exposes `QR scanner` and the live-view guidance. Do not announce raw PIN digits aloud after scan; let the masked PIN field expose its normal reveal control if present.
- App row TalkBack label includes app name and `selected/not selected`; selection is single-choice. The source mode radio labels announce `All compatible apps` or `One app`. During sending announce the source app name once on state transition.
- Volume slider semantics: `iMac speaker volume, {n} percent`; add accessible `Mute`/`Unmute`, and announce confirmed changes politely. Support system font scaling and reduced motion.

## Acceptance

- [ ] QR fills the existing destination, port and PIN fields; it never starts audio automatically.
- [ ] Start still requires explicit tap plus existing Android microphone/capture consent flow.
- [ ] Manual IP/PIN and Bonjour selection remain available if camera is denied or QR is unreadable.
- [ ] Malformed/unsupported pairing data does not partially overwrite valid form values or enter logs.
- [ ] Source mode defaults to All compatible apps; One app mode selects exactly one app and shows its icon/name in idle and sending states.
- [ ] Android 11+ app picker handles package visibility using a narrow launcher query; no broad app-visibility permission is requested by default; empty/error states retain all form values.
- [ ] Changing source app during a stream requires a clear stop confirmation, releases old capture, and opens picker only after stream stops; restarting requires user tap and fresh MediaProjection consent.
- [ ] UI describes playback capture as copying eligible audio; it never implies muting the source app, and handles apps that produce no capturable audio.
- [ ] Volume is clearly labeled as iMac speaker volume and appears only in an active send session.
- [ ] Volume UI is not enabled until a receiver command/acknowledgment contract exists; output-device switching remains out of scope pending user clarification.
