# Cinematic redesign + real Chromium — 1.4.0 (versionCode 15)

Two requests from 2026-10-13 evening:

> "use Chromium directly so you get all of its features… the design at the bottom is fine now, but the
> title bar, the text and the buttons were not cinematic — not like the big American movies. Don't touch
> the bottom."

## 1. Real Chromium, directly

Android's in-app engine (WebView) *is* Chromium, but it is the version Android ships stripped of browser
features: no account/session of its own, no extensions, no full-fledged download UI, no site settings.
The genuinely complete Chromium on a phone is the installed **Chrome**, and code 15 uses it directly
through **Chrome Custom Tabs** (`androidx.browser`):

| Where | Action |
| --- | --- |
| Browser toolbar | New **Chromium** button (globe/spoke icon) — opens the current page in the installed Chrome, full engine, your own session |
| Browser options | *"Open this page in Chromium (Chrome)"* and a **default-engine switch** *"Use Chromium for every page (toggle)"* — when on, every address/search opens straight in Chrome |
| TeraBox share page | The detected-share card now has a second button, **"Open in Chromium (full Chrome)"** |
| Tera tab → file row **Browser** | A chooser explains the difference and lets you pick: *in-app capture engine* (can save the stream the page plays) or *Chromium / Chrome* (full browser, your session) |
| Browser engine dialog | Reports the installed Chromium package + version, whether Custom Tabs is available, and which engine is currently the default |

Honest, explicit trade-off (the app says it in-line): **what Chrome plays cannot be captured by
StreamDock** — the media sniffer only exists inside the in-app engine. So: browse with Chromium, but save
streams with the in-app engine. Nothing is handed to Chrome — no cookies, no headers, no JS bridge; the
gate `tools/verify_cinema_chromium.py` fails the build if any of those ever appear in that path. If Chrome
is not installed, the app offers the Play Store link instead of failing silently.

## 2. Cinematic pass — everywhere except the bottom bar

The bottom navigation is **untouched by design** — its compact 68→52 dp, single-line, auto-sized labels
stay exactly as approved, and the new gate asserts that.

Everything else now speaks the same film-poster language:

- **Display type:** condensed bold, all caps, poster tracking (`CinemaUi.display`). Page titles, hero
  title, brand line, card headlines all use it.
- **Scene kickers:** every section label is now a *title-card* style red rule + tracked small caps
  ("In-App Downloads", "Engine Room", "Browser & Privacy", "Quick Access", …), via `CinemaUi.kicker`.
  The hero keeps its scene numbering: `01 / DISCOVER`.
- **Title bars:** `CinemaUi.titlebar` — scene label, condensed title and a headline hairline, applied to
  the Downloads and Tools pages, and a matching condensed `BROWSER` header with a hairline rule.
- **Buttons:** poster-style — condensed caps with `.06` tracking, sharper 7 dp corners, hairline edge
  (bright red rim on primary, warm-dark with a red-tinted rim on secondary). Height stays the compact
  44 dp from the phone-fit fix, so nothing grows.
- **Backdrop:** flat charcoal replaced by a soft three-stop wash (`backdrop`) so pages read like film
  frames instead of panels; cards got a slightly tighter 12 dp radius and cleaner hairline strokes.
- Warm "credits" off-white (`paper`) is used for supporting copy, keeping the dark/red identity intact.

**Font clamp and fit from the previous build are preserved** (system font scale is pinned to 100 % for the
app, all sizes stay in the compact ranges), so the cinematic look does not undo the "must fit the phone"
requirement.

## Gates

- `tools/verify_cinema_chromium.py` — asserts the bottom bar is unchanged (single-line, auto-sized, 52 dp),
  that the cinematic helpers exist and are actually used, and that the Chromium path performs no cookie,
  header or bridge hand-over.
- Existing gates (URL codec, FFmpeg runtime, browser release) unchanged and still enforced.
- Version: versionCode **15**, name 1.4.0; signer unchanged (`2d3216…`).
