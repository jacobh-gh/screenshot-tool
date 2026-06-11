# Samsung One UI screenshot UX — reference notes

Date: 2026-06-10. Based on web research into One UI (various versions up to
One UI 6/7). The user is migrating from a Galaxy S22 Ultra, so this reflects
the behavior they're used to. Verify specifics against current One UI if
possible (e.g. by searching for the current version's release notes/demo
videos), since this is a "feel" target, not a pixel-perfect spec.

## Trigger

- Standard screenshot gesture (Power + Volume Down, or palm swipe gesture if
  enabled) takes the screenshot as normal.

## Immediate popup

- A small preview thumbnail of the screenshot appears, typically in the
  lower portion of the screen, alongside (or as part of) a horizontal
  toolbar.
- The toolbar includes icons for roughly:
  - **Edit/crop** (pencil icon) — opens Samsung's built-in screenshot
    editor directly on the captured image: crop, draw/markup, text, emoji
    stickers, blur tool for redacting sensitive info, filters.
  - **Scroll capture** — extends the screenshot by capturing additional
    content below the fold, repeatedly, and stitching into one tall image.
    In One UI 6+, holding this button auto-scrolls and captures the entire
    page in one go without repeated taps; it also normalizes brightness
    across the stitched sections.
  - **Share** — opens the share sheet for the captured image immediately.
  - **Delete/dismiss** — discards the screenshot and dismisses the popup.
- The popup/toolbar auto-dismisses after a few seconds if untouched, leaving
  the screenshot saved normally (accessible later via Gallery/notification).

## Crop/edit screen

- Tapping edit opens directly into an editor scoped to that one image —
  no separate "open gallery, find the file, open editor" steps.
- Crop tool offers a draggable crop rectangle with handles, common aspect
  ratio presets, and free-form cropping.
- Save/overwrite vs. save-as-copy choices are typically presented.

## Scroll capture details

- Available when the foreground app/content is detected as scrollable.
- Captures the current screen, then programmatically scrolls and captures
  again, repeating either a fixed number of times or until the user stops it
  / the content stops scrolling (reaches the bottom).
- Stitches captured frames into a single tall image, handling overlap
  between frames.
- One UI 6 added: hold-to-auto-capture (continues until end of content or
  user releases), and brightness normalization across stitched segments
  (useful for apps with dark/light section transitions).

## What to replicate vs. adapt

For our FOSS Pixel/GrapheneOS version, the goal is the *feel* — instant
contextual access to crop and "capture more" right after a screenshot —
not necessarily pixel-identical UI. Reasonable adaptations:

- The popup could be a notification with rich actions, a small floating
  bubble, or a transient overlay window — whichever is actually achievable
  within Android's current overlay/notification permission model on
  GrapheneOS (see `grapheneos-overlay-notes.md`).
- Scroll capture stitching logic will need to be built independently
  (likely via Accessibility Service driving scroll + repeated capture +
  image stitching with overlap detection) since there's no confirmed public
  API for third-party apps to invoke the system's scroll capture directly.
- Drawing/markup/blur tools are "nice to have" — the user's stated priority
  is **crop** and **capture more**; don't scope-creep into a full editor
  unless crop + scroll capture are solid first.
