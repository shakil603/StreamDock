# "The video downloads fine but is ruined" — fixed (1.4.0 / versionCode 16)

Report: downloading a Facebook (or other platform) video produced a file, but the video was unplayable /
ruined. Four separate defects could produce exactly that; all four are now closed and gated.

## What was actually wrong

| # | Defect | Why it ruins the file |
| --- | --- | --- |
| 1 | **Container was never checked** — the app trusted the file name (`media.mp4`) instead of the bytes | WebM/Matroska/TS bytes written under a `.mp4` name, or an HTML error page saved as a "video", is what players report as *corrupt* |
| 2 | **FFmpeg merged any codec pair into MP4** (`-c copy` + `/media.mp4`) | MP4 cannot carry VP9/AV1 + Opus in practice; the merge "succeeded" and produced a file most players (and some Android firmware decoders) refuse |
| 3 | **yt-dlp fallback could write another container**, while the app exported `File(folder, "media.${kind.extension}")` | the produced `.mkv`/`.webm` was exported as `.mp4`, or the requested name simply did not exist |
| 4 | **Parallel ranges on windowed CDN URLs** (Facebook `…?bytestart=…&byteend=…`, `fbcdn`, `/chunk_0001`) | each request returns a *different byte window* of the video; stitching them yields a file of the correct length but scrambled content — the classic "downloads fine, plays broken" |

## What code 16 does

1. **`MediaContainerReader`** sniffs the first 64 bytes of every finished file: ISO-BMFF (`ftyp` brand →
   MP4 vs M4A), EBML (Matroska/WebM), MPEG-TS, FLV, MP3/M4A/OGG/FLAC/WAV, images, ZIP, HLS playlist and
   HTML. Non-media data is **never** reported as a finished download with a new, test-backed guard.
2. **Codec-safe merge:** `NativeMediaProcessor.merge` now takes the two source suffixes and only writes
   MP4 when the parts are MP4-native (H.264/HEVC + AAC/MP3); anything else is merged into **Matroska**
   and the app says so ("merged into Matroska … because those codecs are not MP4-native").
3. **Trust the file that exists:** the transfer worker reads what yt-dlp/NewPipe actually produced, checks
   its container, exports with `FileOutputStream(fd)` + `sync()`, re-opens the destination to confirm the
   exported size equals the downloaded size, and — when the container is not the requested one — renames
   the saved document (`DocumentsContract.renameDocument`) to the correct extension and reports it.
4. **No parallel ranges on windowed URLs:** `DownloadPlanner.volatileChunkUrl` marks Facebook-style
   byte-window/chunk URLs; `probe()` now additionally requires an explicit `Accept-Ranges: bytes` header
   before multi-connection is allowed. Those URLs run on **one honest connection** and the row says why.
5. **Browser-captured + TeraBox saves are verified too:** after export the in-app engine re-reads the
   destination, compares sizes, sniffs the container, and marks the item `EXPORT_FAILED` with a plain
   explanation instead of "Saved" when the bytes do not add up (the received data is kept for retry).

Everything above is test-covered: `MediaContainerTest` (7 cases incl. MP4-vs-M4A brands, TS sync
verification, HTML/playlist refusal, empty-file handling) and `VolatileUrlTest` (Facebook windows vs
ordinary static files).

## Design pass (code 16) and the Home card

- The **TERABOX SHARE card was removed from Home** as requested — the Tera tab remains the single place
  for share links (browse → Tera, or the TeraBox tile).
- Home: hairline under the brand row; quick-access tiles redesigned as **numbered scenes**
  (`01 / WEB — YouTube`, `02 / ENGINE — TeraBox`, `03 / WEB — SoundCloud`).
- Downloads: the summary line became a **stat strip** — large condensed ACTIVE / PAUSED / FINISHED
  numerals over a hairline, with total speed and "everything continues from where it stopped" beneath.
- Bottom navigation untouched (gated).
