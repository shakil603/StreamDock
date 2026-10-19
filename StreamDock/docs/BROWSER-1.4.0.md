# Browser media capture — StreamDock 1.4.0

## What it does

While you browse inside StreamDock, the browser lists the media **the page itself loads or declares**, from any
platform. The browser's **Download button** (the only extra toolbar action) shows that list; each item can be
**played inside StreamDock** or **saved with Android's download service**. See `FIXES-1.4.0.md` for the
crash fix and the button cleanup in this build (versionCode 10).

Two observation paths feed the list (both read-only):

1. `shouldInterceptRequest` records media URLs as the WebView requests them. The request object is never modified,
   answered, cached or replayed; the callback returns `null` and normal page loading continues. This callback runs on
   a WebView thread, so it touches no WebView method — only a volatile page-URL field written on the UI thread
   (calling WebView methods there was the 1.4.0 crash).
2. On page load, a short read-only DOM query collects `video`/`source`/`audio` `currentSrc`/`src` and `og:video`
   meta values. No JavaScript bridge is injected (`addJavascriptInterface` remains forbidden by the release audit)
   and nothing is sent anywhere.

Classification is unit-tested: media extensions (`mp4`, `webm`, `mkv`, `mov`, `ts`, `m3u8`, `mpd`, `mp3`, `m4a`, …)
plus `video/*`, `audio/*` and HLS/DASH mime hints; images, scripts, JSON, HTML, thumbnails/posters and non-HTTPS or
`blob:`/`data:` addresses are rejected. The list is deduplicated, capped at 40 entries and keeps the page URL it
came from as the Referer for playback.

## Playback and saving

- **Play** launches the app's Media3 player with the same `User-Agent` the WebView used and the captured page as
  `Referer` — the same two headers a normal browser playback uses.
- **Save** goes through the existing confirmation dialog and `DownloadManager` path. Website cookies and
  authorization headers are still never attached to a download (unchanged security rule from 1.2.0); if a link
  requires a logged-in session, the app says so instead of leaking credentials.
- HLS (`m3u8`) playback uses the bundled Media3 HLS module; DASH (`mpd`) uses the DASH module.

## TeraBox inside the browser

Opening a TeraBox share page now shows an **"Open in the TeraBox engine"** panel that hands the share to the
native Tera tab (file list, Play/Save) without asking for an account. The browser can also play the page normally,
and whatever stream the page itself loads appears under the video button.

## One-tap link flow

Pasting or sharing a TeraBox link now routes to the Tera tab **and analyzes immediately** — the user pastes once and
nothing else is required. Other platforms keep the existing Home flow (NewPipe first, yt-dlp second).

## Honest limits (tested behaviour, not marketing)

- Pages that use DRM (Widevine) or `blob:`/MSE-only players expose no reusable file link; they stay inside the
  website. The video button explains this instead of pretending success.
- Signed CDN links expire; if playback stops later, the page must be played again to capture a fresh address.
- Saving still depends on the CDN honouring the link without cookies. Failures are reported, never silently retried
  with another engine.
- No device test of 1.4.0 has been performed in this workspace; the unit tests and static audits below cannot prove
  on-device behaviour.

## Verification for 1.4.0

- `93 unit tests, 1 skipped (optional live), 0 failed` — including the new `MediaSnifferTest` (classification,
  mime/Accept hints, rejection of pages/scripts/images/blob/data, dedupe + cap + newest-first ordering, page
  attribution, safe file names).
- `lintDebug`: 0 errors.
- APK gates: Android 9 URL-codec audit, 79 ELF ABI/FFmpeg dependency audit, release-boundary audit (English UI,
  TeraBox engine invariants, no JS bridge, no cookie export) — all passed on the packaged APK, which also contains
  `MediaSniffer`, `shouldInterceptRequest` observation and the `browserPlayUrl` intent path.
