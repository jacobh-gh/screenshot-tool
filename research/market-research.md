# Market research: existing screenshot popup / crop / scroll-capture apps

Date: 2026-06-10. Findings from web search — verify currency before relying
on them, app ecosystems move fast.

## Summary

No FOSS app was found that combines all three of: (1) automatic popup on
screenshot, (2) inline crop from that popup, and (3) scroll/long-screenshot
capture, in the way Samsung One UI does as a single integrated flow.
Closest pieces exist separately:

## Image Toolbox (current daily driver)

- F-Droid: `ru.tech.imageresizershrinker`
  (https://f-droid.org/packages/ru.tech.imageresizershrinker/)
- GitHub: https://github.com/T8RIN/ImageToolbox
- FOSS variant `3.9.0-foss` available on F-Droid, requires Android 7.0+.
- Strong cropping (freeform, aspect-constrained, perspective correction),
  filters, format conversion, OCR, EXIF editing, background removal.
- Gap: no auto-popup-on-screenshot behavior; user has to manually open the
  app and pick the screenshot from the gallery/share sheet. No scroll
  capture.
- License: check exact license before reusing code/UI directly (appears to
  be GPL-3.0 per repo — confirm).

## Screenshot Tile (NoRoot)

- F-Droid: `com.github.cvzi.screenshottile`
  (https://f-droid.org/gl/packages/com.github.cvzi.screenshottile/)
- Provides a Quick Settings tile to take screenshots without root, useful
  reference for how a FOSS app handles screenshot-adjacent permissions and
  Quick Settings integration on GrapheneOS-class devices. Worth reviewing
  its source for permission-handling patterns even though its purpose
  (taking screenshots) differs from ours (reacting to screenshots).

## Long screenshot / scrolling screenshot apps (Play Store)

- "Full Long Screenshot Capture" (`com.pentabit.long.screenshot.capture.full.screen`)
- "Long: Full Screenshot Capture" (`com.appworld.screenshot.capture`)
- "Long Screenshot - Scroll Scree" (`com.cpl.full.longshot.capture...`)
- These are closed-source Play Store apps, generally ad-supported. They
  demonstrate scroll-capture is achievable by third-party apps (likely via
  Accessibility Service-driven scrolling + stitching), but none are FOSS and
  none were evaluated for quality/privacy. Useful as a feasibility signal,
  not as a dependency.

## Built-in OS scroll capture (Android 12+)

- Android 12 introduced a system-level "Capture more" button in the
  screenshot preview, backed by `ScrollCaptureCallback`
  (https://developer.android.com/reference/android/view/ScrollCaptureCallback).
- Most View-based apps support it automatically; WebView/Chrome-based
  content does not reliably support it.
- This is a *system UI* feature (part of the screenshot preview shown by
  the OS), not something a third-party app currently invokes directly via a
  public API in the same flow. A custom implementation likely needs its own
  approach (see AGENTS.md open questions) rather than hooking the system
  API — needs verification.

## Conclusion

Building a custom app is justified — there's a real gap. The crop UI can
likely be built relatively quickly (lots of prior art, including Image
Toolbox's open source code as reference). The "popup on screenshot" and
"scroll capture" pieces are the novel/risky parts and should be prototyped
first.
