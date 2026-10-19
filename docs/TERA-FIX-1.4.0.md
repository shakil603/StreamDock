# TeraBox analysis failure — fixed (1.4.0, versionCode 12)

## What the screenshots showed

`Analysis failed: EOFException` (twice) and once `Write error: ssl=…: Broken pipe`. Those errors came from the
**app**, not from the network: the page reader used Okio's `readByteArray(2_000_001)`, which demands *exactly*
that many bytes and throws `EOFException` when the response is shorter. Every normal share page is 20–130 KB, so
listing failed every single time, on every share, on any network. The "broken pipe" line was a separate transient
reset from the same request path.

## What changed

| Fix | Detail |
|---|---|
| **Correct body reader** | New `TeraEngine.readCapped()` reads until EOF or the 2 MB safety cap and only then rejects oversized answers. Small pages, chunked answers and empty bodies are now handled correctly. |
| **Same for the media probe** | `readUpTo()` replaces a fixed `readByteArray(64)` that would also throw on short responses. |
| **Transport retries** | A reset/EOF/broken pipe is retried (300 ms), then again over **HTTP/1.1 with a fresh connection** (700 ms). Only transport errors are retried — a real service answer is never re-requested. |
| **Mirror-host fallback** | If the connection to the first TeraBox host dies, the same share is tried on the other official hosts (`terabox.com`, `terabox.app`, `teraboxapp.com`, `1024terabox.com`). Service answers (verification, deleted share) are final and never retried elsewhere. |
| **Clear message + retry hint** | A transport failure now says what happened, that it was retried automatically, and suggests switching Wi-Fi/mobile data; the message points at the *Analyze share* button for a manual retry. |
| **Chromium in the browser** | Browser options gained **"Open this page in Chrome"** (Chrome/Chromium with the user's normal session, after a confirmation) and **"Browser engine & updates"**, which shows the Chromium provider Android is using (Android System WebView / Chrome) with its version and an update button, plus where to switch the provider. The in-app browser itself is already Chromium — Android supplies that engine; updating it is the way to get a newer one. |

## Real-network evidence (workspace, JVM, fixed code)

The engine itself was run against the two shares from the screenshots (`-PteraShare=…`, optional live test):

```
LIVE resolve: files=1  origin=https://www.terabox.app/
LIVE file: name=Why.Women.Love.S01E04.480p..WEB-DL.AAC2.0. official dub.mp4  size=164920251  dir=false  dlink=false
LIVE download-link: NOT AVAILABLE -> TeraBox requires verification (code 400310) …
```

```
LIVE resolve: files=1  origin=https://www.terabox.app/
LIVE file: name=Welcome to Waikiki S01E14  720p  hindi + kr.mkv  size=321207617  dir=false  dlink=false
LIVE download-link: NOT AVAILABLE -> TeraBox requires verification (code 400310) …
```

So **listing now works for both shares** with the corrected code (before the fix it could not work at all).

## Download-link reality (measured, not assumed)

For the same shares, every official host (`terabox.app`, `terabox.com`, `1024terabox.com` → `1024tera.com`,
`teraboxapp.com`) answered `/share/list` with `errno 0` and exposed the share record (sign/timestamp/uk/shareid)
in its WAP page, but `/share/download` returned **errno 2 / 400310** on both POST and GET with `jsToken`:

- TeraBox does not mint an original-file download link for an anonymous session right now.
- The app therefore shows the honest verification message instead of pretending success, and does not attempt any
  verification bypass or third-party resolver.
- Original-file downloads need the operator-configured resolver described in `docs/RESOLVER-SETUP.md` (a server
  session owned by the operator — still no password or cookie from the app user).
- Media the TeraBox **page** plays can still be captured by the in-app browser's Download button.

## Verification of this build

- 108 unit tests, 1 optional-live skipped, 0 failed — including new regression tests for exactly these bugs:
  a 20 KB page is read completely and yields its `jsToken`, an oversized response is rejected with the safety-limit
  message, a shorter-than-64-byte media probe still works, mirror candidates stay on official HTTPS hosts and keep
  the share id, a resolver session never builds mirror candidates, and transport failures stay distinguishable
  from service answers.
- The optional live test (`-PteraShare=<link>`) now exists, so the real listing can be re-checked from the command
  line at any time.
- lint 0 errors; URL-codec (Android 9), FFmpeg ABI and release-boundary audits pass on the packaged APK.
- **Not verified:** this build on the phone. The fix addresses a deterministic bug that made listing impossible, and
  listing is proven working off-device; the phone must confirm.
