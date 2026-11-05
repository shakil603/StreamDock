# In-app download engine — StreamDock 1.4.0 (versionCode 11)

## What the user gets

- **Several downloads at the same time.** TeraBox saves, browser-captured media and other in-app saves run
  concurrently (default 3 files; selectable 1–5). The previous build allowed only one transfer at a time.
- **Pause / Resume / Cancel / Restart per file**, right inside the app: Tera tab saves and browser-captured saves
  land in **Downloads → IN-APP DOWNLOADS**, each row with its own buttons, progress bar, speed and live status.
- **Nothing is thrown away.** Pausing or losing the connection keeps every byte already received. Resume continues
  from that offset with a `Range` request; the app never deletes a partial file on failure. A partial file is removed
  only when the export to your chosen folder has succeeded, or when you explicitly cancel.
- **Recovery after a restart.** The queue lives on disk (`filesDir/inapp-downloads/<id>/meta.json` + `data.bin`).
  If Android kills the app mid-download, the item comes back as *queued* on the next start and continues from where
  it stopped; a 15-minute keep-alive worker also brings the service back if the process dies while items are active.
- **Automatic retry.** An interrupted connection is retried with growing backoff (1s → 60s) while the item stays
  running, so a flaky mobile network no longer ends a download permanently.

## Speed: multi-connection transfer, honestly described

Each file is probed once (`Range: bytes=0-0`). If the server answers **206** and provides a validator
(`ETag` or `Last-Modified`), the file is split into parallel segments (default 4, selectable 1–16) that download
simultaneously and write at their own offsets through positional file writes.

- Multi-connection helps most where the CDN throttles **per connection**; in that case total speed often rises
  several-fold. It is the same technique desktop download managers use.
- It cannot multiply your line rate. No app can honestly promise "100×": the server, the CDN policy and your
  network set the ceiling. Measure it: the Downloads tab shows live MB/s, so you can compare 1 vs 4 vs 8 connections
  on your own connection and keep whichever is fastest.
- Multi-connection is switched on **only** when the server proved range support and gave a validator. Otherwise the
  file is fetched on a single connection, because resuming without a validator risks stitching together two
  different versions of a file.
- `If-Range` + exact `Content-Range` checks guard every resumed read; a changed file stops with
  "Restart this download" instead of producing a corrupted file.

## Where it is used

| Action | Path |
|---|---|
| Tera tab → *Save* on a listed file | in-app engine (multi-connection, pause/resume) |
| Browser → captured media → *Save file* | in-app engine |
| Browser page download button (arbitrary web files) | Android DownloadManager (unchanged; Android already runs those in parallel) |
| Home → *Download & save* (NewPipe / yt-dlp extraction) | WorkManager, now **one work item per request** so several extractions can run at once (previously a single "active-transfer" slot blocked all others) |

Website cookies and authorization headers are still never attached to any download.

## Settings

Tools → **DOWNLOAD ENGINE**:

- *Connections per file*: 1, 2, 4, 6, 8, 12, 16 (default 4)
- *Files at once*: 1–5 (default 3)

Both are stored in `download-engine` preferences and apply to new items immediately; a change to the connection
count takes effect for the next file so a running transfer is not disturbed mid-flight.

## Verification

- Unit tests (offline, no network) cover the planner arithmetic: single-connection fallbacks (no ranges, no
  validator, unknown size, small files), complete gap-free segment tiling for 2–16 connections, resume offsets,
  strict `Content-Range` matching including `/*`, validator-change detection, JSON round-trip of a paused item with
  its byte offsets, and the restart rule that a RUNNING item comes back QUEUED with its received bytes.
- Two real arithmetic bugs were caught by these tests during development (blank validator treated as usable;
  `bytes 0-1023/*` rejected) and fixed.
- `lintDebug` 0 errors; the release-boundary, URL-codec and FFmpeg ABI audits still pass on the packaged APK.
- **Not verified here:** behaviour on a real phone/network. The retry, pause and resume paths are exercised only by
  unit tests and code review in this workspace; no media file was downloaded with this engine yet.
