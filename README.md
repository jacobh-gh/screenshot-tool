# Screenshot Toolbar

**Profile target: Main profile ONLY — do NOT install in Private Space.** No
Google Play dependency. Sideloaded debug build via ADB. No root required.

Recreates the Samsung One UI "screenshot taken" flow on GrapheneOS: the moment
a screenshot is taken, a floating toolbar pops up with the thumbnail and
instant **Crop / Share / Delete / Capture more** actions. Crop opens a minimal
editor scoped to that screenshot (drag edges/corners; **pinch to zoom** for
fine control on long screenshots) — no gallery digging. **Capture more** works
One UI-style: each tap scrolls the underlying app one step and adds a frame;
stop by not tapping. Frames are captured with our UI hidden and stitched into
one tall image (overlap detected per-row; sticky headers and the bottom
nav/gesture bar appear once).

## How it works

Two cooperating pieces, both in the main profile:

- A foreground **detection service** watches `MediaStore.Images` for new
  `Screenshots/` files and shows the floating toolbar
  (`SYSTEM_ALERT_WINDOW`; falls back to a heads-up notification).
- An **accessibility service** (optional, for capture more + hiding the system
  screenshot chrome). When it's enabled it becomes the popup trigger for *all*
  screenshots — including Private Space ones — because it sees the SystemUI
  screenshot preview regardless of profile.

**Private Space:** accessibility services only bind for the foreground/primary
user, never for a profile (confirmed via `dumpsys accessibility` — see
`research/grapheneos-overlay-notes.md`). So the app is **main-profile only**;
there is no point installing it in Private Space and it must not be. The
main-profile accessibility service can still see/scroll/capture a foreground
Private Space app, so **Capture more works for Private Space apps** — the
stitched result is saved to the *main* profile's `Pictures/Screenshots`
(accessibility runs there). The original Private Space screenshot itself stays
in Private Space storage and isn't readable by us, so for those the toolbar
offers Capture more / a single-frame Crop rather than acting on the original.

Cropped copies are saved to `Pictures/Cropped/`; stitched long screenshots to
`Pictures/Screenshots/`.

## Build & install

```bash
# Ensure the Android SDK platform-tools (adb) and cmdline-tools (sdkmanager)
# are on PATH — e.g. export ANDROID_HOME=~/Android/Sdk and add
# $ANDROID_HOME/platform-tools and $ANDROID_HOME/cmdline-tools/latest/bin.
./gradlew :app:assembleDebug
# Install to the MAIN profile only. The --user 0 is REQUIRED: a bare
# `adb install` re-propagates the app into Private Space (user 10), which
# we don't want (see Private Space note above).
adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

Do **not** run `pm install-existing --user <private-space>` or a bare
`adb install -r ...` — the app is main-profile only. If it ever shows up in
Private Space, uninstall it there: `adb uninstall --user <id> dev.jacob.screenshottoolbar`.

After a reinstall the accessibility service is unbound and the detection
service stops; re-enable both:

```bash
adb shell settings put secure enabled_accessibility_services \
  dev.jacob.screenshottoolbar/.access.ToolbarAccessibilityService
adb shell settings put secure accessibility_enabled 1
# then toggle "Detection service" on in the app (FGS can't start from adb)
```

**Testing capture more:** use a normal scrollable app — Contacts, a browser,
a comments thread, the GrapheneOS **Info** app. Do **not** use the **Settings**
app: Android blocks overlays (and our toolbar) over Settings for security, so
the popup won't appear there and it's not a valid test target.

Toolchain: system JDK 25, Android SDK cmdline-tools under `~/Android/Sdk`
(`platforms;android-36` + `android-37`, `build-tools;36.1.0`,
`platform-tools`), Gradle via the checked-in wrapper, AGP 9.x with built-in
Kotlin (no standalone Kotlin Gradle plugin). No Android Studio needed.

Run unit tests: `./gradlew :app:testDebugUnitTest`

## Permissions & why

| Permission | Why |
|---|---|
| Photos/media (`READ_MEDIA_IMAGES`) | Detect + read new screenshots ("Allow all" needed) |
| Notifications | Foreground-service notice + popup fallback |
| Foreground service (`specialUse`) | Keep the MediaStore observer alive |
| Display over other apps (optional) | The floating toolbar; falls back to notification without it |
| Accessibility service (optional) | **Capture more** (scroll + stitch) and hiding the system screenshot chrome. Used only to read window state, scroll the foreground app, and take silent screenshots while our UI is hidden — see below. Without it the app still does single-screenshot popup + crop/share/delete. |
| Run at startup (optional) | Restart detection after reboot, only if enabled in-app |

Deleting/overwriting a screenshot always goes through the system's per-file
confirmation dialog (`MediaStore.createDeleteRequest`/`createWriteRequest`) —
the app never gets blanket write access. **No network access.** The
accessibility service is optional and used solely for capture-more/chrome-hide
(no text harvesting, no input interception, no data leaves the device).

## GrapheneOS notes

- Built and tested on a Pixel 9 Pro XL, GrapheneOS 2026060101 (Android 16
  QPR2 base, API 36).
- During development the overlay permission was granted via
  `adb shell appops set --user 0 dev.jacob.screenshottoolbar SYSTEM_ALERT_WINDOW allow`;
  the in-app "Overlay" row deep-links to the Settings toggle for the normal
  path.
- Known limitation: starting the service on boot may be deferred/blocked by
  Android's boot-time FGS restrictions (untested); the Quick Settings tile is
  the manual fallback.
