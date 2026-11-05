# StreamDock 1.8.0 — TeraBox videos play inside the app

**versionCode 20 · versionName 1.8.0**

The user's demand: videos from `1024terabox.com` (and every TeraBox mirror) must
**play and download inside StreamDock itself** — no browser step, no external
player, no repeated error text. And the constraint on record: **1024terabox.com
offers no public API**, so the app must do exactly what the website does.

## What was wrong

When TeraBox refuses to sign a direct file link (its `400310 / need verify_v2`
anti-bot wall), the old flow answered with an error message that told the user to
open the **browser** — and even that path often failed. The user never wanted a
browser step at all.

## What replaced it: a first-party ladder + one in-app check sheet

`TeraEngine.media()` now runs every link source TeraBox's own clients use, in
order, entirely inside the app:

1. **WAP page-state dlinks** — the mobile share page (`/wap/share/filelist`)
   embeds the share record **with signed dlinks** in `window.__INITIAL_STATE__`.
   The new parser slices the balanced JSON and deep-collects file-shaped entries
   (survives payload restructures). Works even when the JSON APIs are walled.
2. **`/share/download` mint** in both shipped forms — POST (Baidu-lineage) and
   GET (current web player) — with the signature hydrated from every first-party
   source: the listing record, `/share/tplconfig` (AList/OpenList lineage,
   channels `dubox` + `chunlei`), `/api/shorturlinfo`, and the WAP record.
3. **`/share/streaming` HLS manifest** — the web player's own manifest endpoint
   with **`fid=<fs_id>`** (the earlier probe used `fid_list`, which answers
   31023) and `type=M3U8_AUTO_720|480|1080&esl=1&isplayer=1`. The manifest plays
   in the in-app player and saves segment-by-segment.

If all three are still walled, the app opens the **in-app TeraBox check sheet**
(`TeraCheckView`): a compact embedded page right inside the Tera tab where the
user completes **TeraBox's own anti-bot step** (slider/tap) — exactly what the
website demands of any visitor. Then:

* the verification cookies TeraBox minted on **its own hosts** are captured
  (`TeraCookies`, hard-scoped allowlist) and the ladder retries **automatically**;
* the file then plays in StreamDock's own player (audio-only mode included) and
  saves through the in-app engine with pause/resume.

The Play/Save retry happens automatically — no repeated error text, no
"tap Browser", no Chromium dialog. Those are gone from the TeraBox flow
entirely (enforced by gates).

## Boundaries kept (and stated honestly)

* **No account password, no imported cookies.** The user is never asked for
  credentials or exported cookie files. The check sheet completes the site's own
  anonymous share check.
* Cookie reads exist in **one** module (`TeraCookies.kt`) and only for the
  TeraBox-family host allowlist — never for any other site, never for the
  user's browser session on other hosts. The gate asserts this.
* The native engine still never evaluates remote JavaScript; the check sheet is
  UI (like the browser), injects no bridge, and stays on TeraBox hosts.
* "Nothing was bypassed" reporting stays: if TeraBox demands its check, the app
  says so and runs the check — it does not pretend or fake success.
* An optional operator resolver under *Advanced* still works for those who run
  one (the vendored MIT `backend/terabox-api` contract is unchanged).

## Verification

Compile + unit tests + five static gates (`verify_browser_release.py`,
`verify_cinema_chromium.py`, `verify_player_browser.py` [incl. ladder asserts],
`verify_url_codec_dex.py`, `verify_ffmpeg_runtime.py`) + signed-APK inspection.
No emulator/KVM here — the live TeraBox check and playback need the phone.
