# GrapheneOS overlay & permission considerations

Date: 2026-06-10. Based on web research — verify against current GrapheneOS
behavior on-device before committing to an architecture, since permission
handling is exactly the kind of thing GrapheneOS tightens between releases.

## SYSTEM_ALERT_WINDOW ("Display over other apps")

- Standard Android permission for drawing overlay windows
  (`TYPE_APPLICATION_OVERLAY`).
- Since Android 12, apps can opt out of having overlays drawn over them via
  `HIDE_OVERLAY_WINDOWS`, and the OS generally made overlays harder to grant
  to reduce overlay-based malware/phishing.
- A GrapheneOS discussion thread ("Cannot give app permission to 'Display
  over other apps'") suggests there can be friction granting this on
  GrapheneOS specifically — investigate the current state on-device:
  Settings → Apps → [app] → Advanced/Permissions → "Display over other
  apps" / "Appear on top". Confirm whether a sideloaded debug build can get
  this permission at all, and whether any special toggle (e.g. enabling via
  `adb shell appops set`) is needed during development.

## Screenshot detection permissions

- Android 14+ added `registerScreenCaptureCallback` which reportedly
  requires no special permission for detecting that a screenshot was taken
  (in your own activity's context — confirm whether it extends to detecting
  screenshots taken in *other* apps, which is what we need).
- Pre-Android 14 approach: `ContentObserver` on
  `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`, requires storage/media
  read permissions (scoped storage rules apply — `READ_MEDIA_IMAGES` on
  Android 13+).
- Need to determine: on the target device's current GrapheneOS/Android
  version, which mechanism actually fires reliably for screenshots taken
  system-wide (not just within our own app), and what permissions GrapheneOS
  requires/grants for it.

## Accessibility Service (likely needed for scroll capture)

- If scroll capture requires programmatically scrolling the foreground app
  and capturing repeated frames, this likely means an `AccessibilityService`
  with `canRetrieveWindowContent` and the ability to perform scroll
  gestures/actions, plus a way to capture the screen content (e.g.
  `MediaProjection` for screen capture, or accessibility node-based
  rendering — MediaProjection is more likely to give pixel-accurate
  captures).
- `MediaProjection` requires user consent via a system dialog each time (or
  can be made persistent depending on API level/foreground service setup) —
  this is a standard Android permission, not GrapheneOS-specific, but the
  UX of re-prompting needs consideration for a "feels instant" experience.
- Accessibility services are heavily scrutinized by users for privacy
  reasons (this audience cares a lot about that) — the app should be very
  transparent about what the accessibility service does and does not do,
  and ideally make it optional (crop-only mode without scroll capture should
  work without granting accessibility access).

## General GrapheneOS posture

- GrapheneOS ships with stricter defaults around sensors, storage scoping,
  and background activity than stock Android/Pixel OS, on top of standard
  AOSP/Android privacy changes. Nothing found suggests these specific APIs
  (ContentObserver, MediaProjection, Accessibility) are blocked outright,
  but expect more explicit user-facing permission prompts and possibly
  needing to explain to the user (in-app) why each permission is requested.
- Recommend designing permission requests to be incremental and
  purpose-scoped: e.g. base "crop on screenshot" feature requests minimal
  permissions; "scroll capture" is an opt-in upgrade that requests
  Accessibility/MediaProjection only when the user enables that feature.

## On-device verification results (2026-06-10, GrapheneOS 2026060101, Android 16 QPR2)

- **Screenshot storage is per-profile.** A screenshot taken while a Private
  Space app is foregrounded is saved to the *Private Space* user's (user 10)
  `Pictures/Screenshots/`, NOT the main profile's. A main-profile MediaStore
  observer never sees it, and main-profile apps cannot read Private Space
  storage at all. The original "one main-profile observer covers everything"
  assumption is therefore false.
- **Resolution: dual install.** The same APK is installed in both profiles;
  each instance observes its own profile's MediaStore and all data stays
  within its profile (no cross-boundary access in either direction).
  Sideloaded apps do NOT appear in Private Space Settings → "Install
  available apps" (that list only covers store-installed apps); use
  `adb shell pm install-existing --user 10 dev.jacob.screenshottoolbar`
  after the normal `adb install` instead. Runtime permissions must be
  granted per profile (`pm grant --user 10 ...`).
- **Detection verified in both profiles**: a real system screenshot
  (`input keyevent KEYCODE_SYSRQ`) fires exactly one detection event in the
  owning profile's service instance, within ~1 second.

## System screenshot chrome — SystemUI ids (2026-06-11, GrapheneOS BP4A.260205.002 / Android 16)

Discovered by dumping accessibility node trees while the screenshot preview is up:

- Preview window root package: `com.android.systemui`.
- Preview thumbnail: `com.android.systemui:id/screenshot_preview`
  (ImageView, clickable, content-desc "Edit screenshot" — do NOT click, it
  opens the editor). Animates from center toward bottom-left over ~1s.
- Container: `com.android.systemui:id/screenshot_static`.
- Action chips: `com.android.systemui:id/screenshot_actions` containing
  LinearLayouts with content-desc "Share screenshot" / "Edit screenshot".
- No dismiss button and no node exposing `ACTION_DISMISS`.

**Dismissal that works:** `dispatchGesture` a fast horizontal fling on the
preview rect toward the left screen edge (preview center → x=1, ~40ms).
Gesture paths must stay on-screen — a negative end coordinate throws
`IllegalArgumentException: Path bounds must not be negative` and crashes the
service. The preview animates, so the dismiss is attempted at 250/800/1600ms
to catch it. Verified: `screenshot_preview` node count goes 2 → 0 and only the
app's own overlay toolbar remains. Fragile by nature (depends on these ids /
fling behavior); gated behind a user toggle and only active while the app's
DetectionService runs.

## Capture-more prototype findings (2026-06-11, GrapheneOS BP4A.260205.002 / Android 16)

Measured via a temporary accessibility-service probe (since removed):

- **`AccessibilityService.takeScreenshot()`** completes in ~33ms but is
  rate-limited: a second call <~600ms after the previous one fails with
  `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (code 3). Successful retries
  land ~605ms after the prior call's start. **Use a ~700ms minimum interval
  per frame** in the capture loop. No MediaProjection consent dialog and no
  system screenshot chrome — silent, as desired.
- **Scrolling:** `ACTION_SCROLL_FORWARD` on the largest scrollable node works
  for both standard views (Settings `ScrollView`) and `WebView` (Vanadium /
  Wikipedia both returned `true`). Node-scroll is the primary path; a
  `dispatchGesture` swipe fallback is kept for custom views that
  under-report scrollability.
- Implication: a long page capture runs ~1.5 frames/sec; a 10-frame stitch is
  ~7s. A visible progress indicator is needed so that feels intentional.

## Accessibility services only bind for the foreground user — never for the Private Space profile (2026-06-11)

**Definitive root cause** (GrapheneOS BP4A.260205.002 / Android 16), from
`adb shell dumpsys accessibility`:

- User 0 (main): `Bound services: {Service[label=Screenshot Toolbar ...]}` —
  enabled AND bound.
- User 10 (Private Space): `Enabled services: {…ToolbarAccessibilityService}`
  but `Bound services: {}` — enabled per settings, **never bound**.

This is core Android accessibility architecture: `AccessibilityManagerService`
binds accessibility services for the **foreground/primary user only**. A
profile (Private Space, like a managed work profile) does not get its own
accessibility service bound. Installing our app + enabling its a11y service in
Private Space can never yield a live bound service there, and
`takeScreenshot`/gesture scrolling (which need a bound service) can't run from
the Private Space instance.

NOT caused by: GrapheneOS exploit protection / MTE (irrelevant), the
DetectionService "Volume external_primary not found" crash (fixed separately),
or the private profile being stopped (that only applies when *locked*).

`AccessibilityManager.getEnabledAccessibilityServiceList` reports the service
as *enabled* in Private Space (it reads the secure setting), so a usable check
must also confirm a live bound instance — hence
`isUsable = isGranted && instance != null`.

### Circumvention possibility (untested, complex)
Accessibility operates on the whole foreground display, so the **main-profile
(user 0) bound service can likely see and act on Private Space app windows**
when one is foreground. In principle capture-more / chrome-dismiss for Private
Space content could be driven by the user-0 service rather than a user-10
instance. Caveats:
- The trigger today is the user-10 DetectionService (Private Space screenshots
  save to user-10 storage); bridging user-10 → the user-0 service is cross-user
  IPC under GrapheneOS's strict profile isolation.
- Per-feature prefs are per-user: "hide system chrome" toggled in the Private
  Space app instance writes user-10 prefs, but the bound service reads user-0
  prefs. This likely explains why chrome-dismiss appeared not to work in
  Private Space — it was toggled in the wrong profile's instance. **Retest with
  the toggle ON in the MAIN-profile app.**

Current decision: keep both accessibility features **main-profile only** and
degrade the Private Space UI honestly (no dead button/toggle).
