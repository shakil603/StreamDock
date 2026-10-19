# StreamDock 1.3.0 — in-app TeraBox share engine

Version 1.3.0 (versionCode 8) adds a native TeraBox section: paste a public share link, see the files, then **Play** or **Save**.
The end user is never asked for a TeraBox password, an account cookie or a browser session export.

## What was built

| File | Role |
|---|---|
| `app/src/main/java/org/streamdock/app/terabox/TeraEngine.kt` | Share-link validation, share resolution, paged file listing, share-record retrieval (first-party `/api/shorturlinfo`, WAP page fallback), download-link request, bounded metadata reads, early media-byte probe. |
| `.../terabox/TeraTransfer.kt` | Streaming download with bounded retries, validated `Content-Range`, `If-Range` resume, size-mismatch rejection and HTML/JSON error-page rejection. |
| `.../terabox/TeraBoxController.kt` | The Tera tab UI: link input, file list with sizes, Play/Save actions, player, optional resolver address. |
| `TransferWorker.kt` | New `teraUrl` branch: TeraBox file transfer runs through the same foreground worker, export and cancel behaviour as other transfers. |
| `MainActivity.kt` | Bottom navigation gains a Tera tab; a pasted TeraBox link (including shared text intents) routes there automatically. |
| `res/mipmap-*`, `mipmap-anydpi-v26` | The selected cinematic black/red **SD icon** is now the launcher icon (legacy PNGs, round variant and adaptive background). |

Deliberate limits in this engine: no JavaScript evaluation, no WebView, no cookie import, no third-party downloader site, no proxy rotation, no attempt to solve a verification challenge. Requests are restricted to TeraBox-family HTTPS hosts (`TeraPolicy`), the OkHttp client refuses redirects outside that set, and metadata bodies are size-capped.

## Live first-party probe with the user's sample link (2026-10-11)

Performed from the workspace network with only normal anonymous session cookies; no account, no password, no third-party resolver, no verification solving.

1. Share page: HTTP 200, `jsToken` present.
2. `/share/list`: `errno 0`, 1 MP4, 164,920,251 bytes, `dlink` empty (expected).
3. `/api/shorturlinfo` (with and without `jsToken`): `errno 2`.
4. WAP page `/wap/share/filelist?surl=…`: HTTP 200, and its HTML **does** embed the share record — `shareid`, `uk`, `sign`, `timestamp`, `fs_id` — which the engine now parses as text (`parseWapRecord`).
5. `/share/download` POST and GET, with the correctly parsed record, the page `jsToken` and standard fields: **`errno 2`, no `dlink`**, in both `dubox` and `chunlei` channels.

Conclusion: for this share on this network, file **listing works**, but TeraBox did not mint a download link anonymously. No media bytes were fetched (the only byte probe was the 1 KB signature check used by the Play path, and it was never reached). This is exactly the case the app now reports honestly instead of faking success:

> "TeraBox listed the files but did not issue a download link in this session. Some shares require an operator-configured resolver — you can enter one under Advanced. Nothing was bypassed."

This result does **not** prove that every share behaves this way, and it does not prove that the phone will behave like the workspace network.

## Optional operator resolver (Advanced)

If direct resolution cannot mint a download link, the Tera tab accepts an HTTPS resolver origin (for example `https://resolver.example.com`) that implements the reviewed `Paidguy/terabox-api` MIT contract: `GET /api/resolve?link=…` returning `{ "status":"ok", "files":[ { "fs_id", "file_name", "size_bytes", "isdir", "stream_url" } ] }`. The app then streams `stream_url` through the same validated transfer path, with the resolver origin added to the host allowlist for that session only. This is the "operator-owned service, no end-user cookie" option; it is optional and nothing is contacted unless the user enters an origin.

## Optional: run the reviewed resolver locally

```bash
git clone https://github.com/Paidguy/terabox-api   # MIT, revision 4d3149a22888018375a933d1c0c0ebc3eef32fb6
cd terabox-api && npm install
# wrangler.toml: set TERABOX_COOKIE (operator-owned account session) and RATE_LIMIT as desired
npx wrangler dev            # local development
npx wrangler deploy         # operator-controlled deployment
```

The upstream project requires its own operator session; that is a server-side configuration, not an end-user login in StreamDock. The clone lives in `StreamDock/backend/terabox-api/` for reference and is deliberately excluded from the delivered source ZIP.

## Verification performed for 1.3.0

- `newpipe-extractor:test` + `testDebugUnitTest`: **86 tests, 1 skipped (optional live), 0 failed**, including new offline TeraBox tests: link policy borders, host allowlist, resolver-address validation, `jsToken` extraction, WAP record parsing, errno-to-message mapping (verification reported as verification), file-list parsing for both the direct and resolver shapes, media/audio signature checks, content-type rejection, `Content-Range` validation and resume safety.
- `lintDebug`: **0 errors** (77 warnings, mostly dependency-version notices).
- APK gates: Android 9 `URLDecoder/URLEncoder` String-overload audit, 79 ELF files ABI/dependency audit (arm64-v8a), release-boundary audit (English UI, engine invariants: no cookie import, HTTPS TeraBox hosts, resume validation, explicit verification errors, no JavaScript bridge, no cookie export).
- Artifacts: `deliverables/StreamDock-1.3.0-arm64-debug.apk` (68,752,162 bytes, SHA256 `b8c97cfe2f400e8d07773b045b8c1387ea90106d1a899af768b133c9473b62ed`), launcher icon confirmed as `mipmap-anydpi-v26/ic_launcher`, engine classes `TeraEngine`/`TeraTransfer`/`TeraBoxController` and the `TERABOX SHARE` section verified inside `classes.dex`, same signing certificate as earlier builds; source `deliverables/StreamDock-1.3.0-Android-Source.zip` (SHA256 `c1fe9491ba8bc831689bb05e377280940ff1a98b567a9fdc76a6841bfd49f73c`).

## Packaging recovery note

An earlier 1.3.0 export was incomplete: the workspace restore dropped some new source/icon files (and the first re-build therefore produced an APK containing the engine-less tree). The completed tree was restored from the 1.3.0 source archive, rebuilt and re-verified; the superseded 1.2.0 APK was removed from this workspace so that the finished 1.3.0 APK fits the workspace size limit. 1.2.0 source remains available for rollback rebuilds.

## What is NOT verified

- No real-device run of 1.3.0. Static gates and unit tests cannot prove live TeraBox extraction, playback or download on the Symphony Z25 / Android 9.
- The sample share could not be downloaded from this network; the app will show the honest message above for it unless a working resolver is configured.
- The optional resolver path was not exercised against a live deployment.
- Playback uses Media3 streaming with the resolved URL and Referer header; seeking depends on the CDN honouring ranges.

## Update policy preserved

NewPipe remains primary for supported sites; a NewPipe failure still never silently switches engines; the Android 9 URL-codec and FFmpeg runtime fixes are untouched; no app-added quotas anywhere.

## 1.4.0 update

A pasted or shared TeraBox link is now routed to the Tera tab **and analyzed immediately** (one paste, no extra taps). The built-in browser also detects TeraBox share pages and offers to open them in the engine. An operator resolver is still the supported path when TeraBox refuses to mint an anonymous download link — see `RESOLVER-SETUP.md`.
