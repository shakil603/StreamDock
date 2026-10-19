# StreamDock 1.7.0 — unified player, real site icons, a browser that remembers

**versionCode 19 · versionName 1.7.0** — three requested corrections, no design change
(Frozen Glass stays exactly as approved on the phone).

## 1. Real website icons

Quick Access tiles used to show invented gradient badges. They now show the **icons the
sites themselves publish** — the real YouTube red mark, the SoundCloud cloud, the TeraBox
blue hexagon — like an iWebs-style shortcut row.

* `SiteIcons.kt` loads icons in a trusted order: icons bundled in `assets/sites/`
  (YouTube, SoundCloud, TeraBox + mirrors, Vimeo, Dailymotion) → the icon hosted on the
  site's own origin (`/favicon.ico`, `/apple-touch-icon.png`) → the DuckDuckGo icon
  mirror → the Google favicon service.
* Every result is cached on disk and in memory, rounded into the same glass-object look.
* Tab list and other host rows use the same system.

## 2. One player interface for video and audio

The inline preview wells (Home card and Tera card) are gone as players; **all playback
lives in one screen, `player/PlayerActivity`**, attached to **one engine,
`player/PlaybackService`** (a media3 `MediaSessionService`):

| Feature | Where |
|---|---|
| Audio-only mode (NewPipe listening) | headphones button — switches the queue to the audio track and shows the aurora artwork panel |
| Background / screen-off listening | the service keeps playing with a media notification: play/pause, previous, next, stop |
| Play/pause, seek + times | bottom glass panel |
| ±10 seconds | center buttons and double-tap on the side of the picture |
| Previous / Next | queue of every analyzed stream (selected one first) |
| Repeat off / all / one | repeat button |
| Speed 0.5× – 2× | speed chip |
| Rotate / fullscreen | rotate button |
| Lock | hides all controls, immersive; Unlock chip returns them |
| Brightness / volume | vertical edge swipes |
| Headphones unplug | playback pauses automatically (becoming-noisy) |
| Audio focus | gained/lost handled by the engine |

Entry points: Home “Play preview” / “Listen (audio only)”, TeraBox file “Play”, and the
browser’s captured-media “Play” all open this same screen. Separate audio+video streams
are merged by the engine exactly as before.

## 3. The browser now behaves like a browser

* **Tabs** — the count button in the header opens the tab list: switch, close, new tab.
* **Saved browsing session** — every open tab (URL, title, full navigation state) is
  persisted and restored after restart, so a session survives closing the app.
* **History** — visited pages list, open-from-history, clear.
* **Bookmarks** — bookmark the current page, open, remove.
* **Find on page** — next/previous match with a match counter.
* **Desktop site** — desktop UA toggle with reload.
* **Text size** — 70%–200% zoom, remembered.
* **Share this page** — system share sheet.

Everything the browser already enforced is unchanged: HTTPS-only, no file access, no
certificate bypass, no native JS bridge, no third-party cookies, no cookie export,
helper-site consent, TeraBox three-step card, media capture for Play/Save.

## Unchanged

Bottom bar (structure, labels, 52 dp rows, routing), Frozen Glass visuals, download
engine (multi-connection, pause/resume, container verification, honest failure wording),
TeraBox flows, runtime version footer, all 1.6.x behaviour.

## Environment limits

No emulator/KVM in the build sandbox: verified by compile + unit tests + static gates
(`verify_browser_release.py`, `verify_cinema_chromium.py`, `verify_player_browser.py`,
`verify_url_codec_dex.py`, `verify_ffmpeg_runtime.py`) + signed-APK inspection.
Playback, notifications and page rendering need the phone test.
