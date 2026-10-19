# StreamDock 1.6.0 — “Frozen Glass”

**versionCode 18 · versionName 1.6.0**

This release replaces the rejected cinematic/flat theme with the design language the user
picked from the 3D concept renders: **frosted glass, holographic rims, chrome wordmark,
ice-blue progress and an aurora backdrop.** Nothing about downloading, resolving, TeraBox,
the browser or the bottom navigation changed.

## What the user asked for

> “I don't like the cinematic style; I want a realistic 3D design.”
> then, after the renders were shown: picked the frosted-glass / holographic render and
> “কালার আগেরটাই থাকবে” — keep that colour scheme.

So the app now reads as a set of **physical glass objects** in a pale silver-blue studio,
not as flat rectangles on black.

## The five primitives (`DockVisuals.kt`)

| Primitive | What it does |
|---|---|
| `GlassSurface` | Drawable that draws every slab: vertical body wash → specular bloom in the top-right → bottom shade (thickness) → catch-light along the top edge → holographic rim (white→cyan→violet→peach). Rebuilt on size change only. |
| `AuroraBackdrop` | Page background: four-stop silver sky + four soft aurora pools (cyan, violet, peach, cyan) + light film grain, cached per size. |
| `ChromeText` | Chrome wordmark: a polished metal sweep painted through the glyphs — used for “StreamDock” and “Browser”. |
| `GlassProgress` | Hand-drawn progress: glass track, ice gradient fill (`#8FE6FF → #3FB7F2 → #5C7BFF`) with a glowing top edge. |
| `Dock.depth()` / `Dock.pressable()` | Real elevation with a **coloured** shadow (API 28+), and a 0.97 scale-down “press” spring on every clickable slab. |

Manrope (300/400/600/800) is bundled in `assets/fonts/` and used for all text, so the
typography is identical on every device instead of depending on the system sans.

## Applied everywhere

* `CinemaUi` — the shared kit — is rewritten in place. Its public names (`background`,
  `surface`, `red`, `muted`, `paper`, `hairline`, `text`, `display`, `kicker`,
  `titlebar`, `button`, `card`, `nav`, `shape`, `backdrop`) are unchanged, so Home, Tera,
  Browser, Downloads and Tools all moved to the new language at once with no behaviour edit.
* New helpers: `CinemaUi.tile()` (glass quick-access tile), `CinemaUi.progress()`,
  `CinemaUi.field()` (glass text field with ice focus ring), `CinemaUi.spinnerChrome()`.
* Home: chrome wordmark + glass “FREE” pill, glass search pill, ocean hero card (generated
  key art in `assets/dock-hero.jpg`) with the copy pinned to the bottom over a light veil,
  three glass site tiles with gradient badges, and glass form cards.
* Downloads: ice-gradient progress bars, glass stat strip, glass action pills.
* Tera: same glass cards, glass input + resolver fields, glass progress.
* Browser: glass header, glass address pill, glass round tool buttons, frost TeraBox card,
  light-system-UI chrome.
* Theme: `Theme.MaterialComponents.Light.NoActionBar`, ice primary `#188FD6`, transparent
  status bar with dark icons, pale navigation bar.

## Bottom bar

Structure, 5 items, order, 52 dp cell height, single-line auto-sized labels and tap routing
are **byte-for-byte the same logic as the approved version**; only the surface changed from
a flat dark strip to a floating glass pill (same compact height). `tools/verify_cinema_chromium.py`
still asserts the label guard, the height and the tap route on every build.

## Launcher icon

Regenerated from the same language: pale aurora field, frosted rounded slab, chrome **SD**,
ice-blue download chip. All densities (`mdpi…xxxhdpi`) plus adaptive
`ic_launcher_foreground` / `ic_launcher_background` layers.

## Unchanged and re-verified

* Download engine, planner, multi-connection, pause/resume, container verification, export
  (code-16 behaviour) — untouched.
* TeraBox three-step browser card and “Save this TeraBox file?” → in-app engine route
  (code-17 behaviour) — untouched.
* Version footer still read at runtime from `packageManager.getPackageInfo` — now shows
  1.6.0 because the installed package says so, not because a string says so.

## Environment limits

There is no emulator or KVM in the build sandbox, so this release is verified by
compile + unit tests + DEX/resource gates + signed-APK inspection. The visual result must be
confirmed on the Symphony Z25 itself.
