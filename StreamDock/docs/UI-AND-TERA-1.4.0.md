# Play/Save verification limit + phone-fit UI — 1.4.0 (versionCode 13)

This build answers the two 2026-10-13 screenshots: **Play** and **Save** both failing with
`TeraBox requires verification (code 400310)` on a share whose file list now works, and the interface
looking oversized / not fitting the phone screen.

## 1. Why a public TeraBox file can still refuse Play and Save (honest answer)

Listing a share and *minting a direct file URL* are two different permissions in TeraBox:

| Step | Endpoint | Anonymous (no account) |
| --- | --- | --- |
| List files of a share | `/share/list`, `/api/shorturlinfo`, WAP `filelist` | **works** (proved on-device and in the JVM live test) |
| Mint a direct download/stream URL | `/share/download`, `/share/streaming`, `/api/streaming` | **refused** — `errno 2` / code **400310** on every public host and method |

We re-probed all of them for this build, including the exact call the web share page makes
(`/share/download` with `app_id`, `web`, `channel:"dubox"`, `clienttype`, `jsToken`, `dp-logid`).
The anonymous session is rejected by the server. The `dp-logid` variant behaves the same because the
check is on the account/session, not the token. **StreamDock does not and will not bypass verification —
no account password, no imported cookies, no third-party cookie pools.**

So the code-12 message ("Playback is not ready: TeraBox requires verification") was truthful but a dead
end. Code 13 keeps the truth and adds the only honest path that actually plays and saves these files:

## 2. What code 13 does instead

1. **Browser path on the Tera tab** — a new **"Browser"** button on every file row (and
   **"Open share in browser"** under the link field) opens the share in the built-in Chromium browser.
   The TeraBox page has its *own* session for the page it served, so its own player can play the file.
2. **The browser now saves real streams.** When the page plays, StreamDock's capture button lists the
   media the page loaded. Saving an HLS stream (`m3u8`) previously failed silently (Android's download
   manager would have stored the playlist text). Code 13 adds a real HLS downloader to the in-app engine:
   - master playlist → best variant → media playlist → segments fetched **in order** and appended;
   - `#EXT-X-KEY` (DRM/AES) playlists are **refused with a plain error**, never faked;
   - segments are stored compactly (URIs + sizes) so a paused stream resumes **at a segment boundary**
     with every received byte kept, exactly like the byte-range engine;
   - progress shows `3/64 segments • 5% • 12.4 MB of ~11 min`.
3. **Failure text is short and points at the next step** instead of repeating the raw error:
   *"Playback is not ready: this file needs TeraBox verification, so no public direct link exists
   (code 400310). Tap Browser on the file row: the TeraBox page can play it there, and Save works from
   the browser's media button."*
4. **Operator resolver unchanged** — if you run a resolver service (`docs/RESOLVER-SETUP.md`), Play/Save
   work fully natively because that service holds its own TeraBox session.

## 3. Phone-fit UI (buttons/typography looked oversized, bottom bar labels wrapped)

- **Font scale is clamped to 100 %** for the app's own screens (`FontFit`): the layout is already sized
  in `sp`, so a large system font setting previously inflated titles/buttons and wrapped the bottom bar.
- **Bottom bar rebuilt**: icon + **single-line auto-sized label** (7–10 sp, ellipsised), cell height
  68 → 52 dp, so "Downloads"/"Browser" can never wrap again at any font setting.
- **Everything scaled down ~20–25 %**: hero 238 → 202 dp, hero title 30 → 24 sp, page titles 30–32 →
  23–26 sp, card titles 21–24 → 17–19 sp, section cards padding 16/15 → 14/12 dp, page padding
  20/18 → 15/13 dp, buttons 48 → 44 dp, search bar 54 → 48 dp, player boxes 190 → 158/160 dp,
  quick-access tiles 97 → 80 dp, browser header/search/nav rows 48 → 44 dp.
- Dark/red identity, English UI and every existing feature are unchanged.

## 4. Evidence for this build

- **JVM live HLS test** (real public stream, `-PhlsStream=`): `mode=HLS segments=64 duration=634.567`,
  saved `bytes=25004000 segments=3 complete=true` — i.e. real bytes appended into one playable file.
  A first run failed with `HTTP 404 on segment 1`, which exposed a real bug (relative segment URIs were
  resolved against the master playlist instead of the chosen variant) — fixed, then the test passed.
- **Unit tests**: 116 tests / 3 skipped / 0 failed (includes 6 new HLS parsing/round-trip tests).
- **Static gates**: URL-codec, FFmpeg runtime and browser-release verifiers all PASS; signer unchanged
  (`2d3216…`); `lintDebug` clean.
- The on-device listing fix from code 12 is untouched.

**What still cannot be promised:** a share that TeraBox has put behind account verification cannot be
downloaded by StreamDock natively, and a player that uses blob-only or DRM delivery will not expose a
reusable stream. In those cases the app says so plainly.
