# screenshot-toolbar

**Profile target:** Main profile (no Google Play dependency expected). Should
be installable via F-Droid/Obtainium-style sideloading or built from source.

## Goal

Recreate the Samsung One UI "screenshot taken" experience on GrapheneOS /
stock Pixel UI:

1. **Instant popup on screenshot** — as soon as a screenshot is taken, a
   small floating toolbar/preview appears near the screenshot notification
   (like One UI's bottom toolbar), offering quick actions.
2. **Inline crop** — tapping the crop/edit action opens a fast cropping UI
   directly on the screenshot, without needing to open a separate gallery
   app or a heavyweight image editor.
3. **"Capture more" / scroll capture** — an action that extends the
   screenshot downward (or in whatever scroll direction is available) to
   stitch a long/scrolling screenshot of the current screen, similar to One
   UI's scroll capture and Pixel's "Capture more".

The user currently uses [Image Toolbox](https://github.com/T8RIN/ImageToolbox)
(F-Droid: `ru.tech.imageresizershrinker`) for manual cropping after the fact —
it's good but requires manually opening the app and picking the file. The gap
is the *instant, contextual popup* and *scroll capture*, which don't exist
together in any FOSS app we found during research (see `research/`).

## Why this is "ambitious"

- No existing FOSS app combines "auto-popup on screenshot" + "inline crop" +
  "scroll capture" in one package (see `research/market-research.md`).
- Scroll capture in particular is technically hard without root: Android's
  own `ScrollCaptureCallback`/`ScrollCapture` framework (Android 12+) is
  mostly wired up for system UI (the Recents/screenshot UI), not freely
  invokable by third-party apps in the same way. A from-scratch
  implementation may need creative approaches (accessibility service +
  programmatic scroll + stitch captured frames, or piggybacking on the
  system's scroll capture session if accessible). This needs real
  investigation/prototyping — don't assume it's a quick win.
- Floating overlays require `SYSTEM_ALERT_WINDOW` ("display over other
  apps"), which GrapheneOS treats more cautiously (see
  `research/grapheneos-overlay-notes.md`). The popup approach needs to work
  within whatever GrapheneOS actually allows, and degrade gracefully
  (e.g. fall back to an enhanced notification action / quick-tile / share
  intent if a true overlay isn't viable).

## Suggested approach (non-prescriptive — frontier agent should validate)

A reasonable shape for this app, but feel free to deviate based on what's
actually feasible once you prototype:

- A background service using a `ContentObserver` on
  `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (or, on Android 14+, the
  newer `registerScreenCaptureCallback` API) to detect new screenshots.
- On detection, show some kind of fast-access UI — a heads-up
  notification with action buttons, a floating bubble/overlay (if
  permission obtainable), or a quick-settings tile — leading into:
  - A lightweight, fast crop screen (could reuse/embed cropping logic
    inspired by Image Toolbox or a crop library, written fresh as needed).
  - A "capture more" flow for scroll capture — this is the riskiest /
    most novel part and should be prototyped early to de-risk the rest of
    the project.

## Open questions for the frontier agent

- Is there a way to trigger the system's built-in scroll capture
  programmatically from a third-party app context, or does it require an
  Accessibility Service to drive scrolling + repeated screenshots + image
  stitching?
- What's the actual overlay/permission experience on current GrapheneOS for
  `SYSTEM_ALERT_WINDOW` — is "Display over other apps" grantable for a
  sideloaded app, and does it require special toggles (Settings → Apps →
  [app] → Advanced)?
- Should this integrate with or extend Image Toolbox (it's open source,
  GPL-3.0/Apache — check license compatibility) rather than building a crop
  UI from scratch, or is a standalone minimal app simpler/safer?
- What's the minimum Android API level worth targeting given this is for a
  Pixel 9 Pro XL on the latest GrapheneOS (check current GrapheneOS release
  notes for the Android base version)?

## Layout

```
screenshot-toolbar/
├── AGENTS.md              # this file
├── README.md              # what it does, build/install, permissions
├── research/
│   ├── market-research.md         # existing apps surveyed, gaps found
│   ├── samsung-ux-reference.md     # detailed Samsung One UI behavior notes
│   └── grapheneos-overlay-notes.md # overlay/permissions + on-device findings
└── app/                    # Android app source (phase 1: detect → popup → crop)
```

Phase 1 (detect → popup → inline crop, dual-profile install for Private
Space) is implemented; see README.md. Phase 2 (scroll capture) has not been
started. Design spec: `docs/superpowers/specs/2026-06-10-screenshot-toolbar-design.md`
(repo root).
