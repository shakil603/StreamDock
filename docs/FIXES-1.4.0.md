# StreamDock 1.4.0 — fixes on top of the 1.3.0 project

Version name stays **1.4.0**; versionCode is now **10** so it installs over the previous 1.4.0 build.

## 1. Crash / "thrown out of the app" — root cause found and fixed

`BrowserActivity` observed media through `shouldInterceptRequest`, which Android calls on a **WebView
background thread**. The previous build read WebView state there (`web.url`, guarded by `ready`). Calling
WebView methods off the UI thread is illegal and terminates the process, which is why the app closed when
browsing — including when tapping things in the browser or its options.

Fixes:
- the callback now reads only a `@Volatile pageUrl` field that is written on the UI thread
  (`onPageStarted`, `onPageFinished`, `navigate`);
- the whole callback body is wrapped in `runCatching`;
- the `DownloadListener` body is wrapped in `runCatching` as well;
- `goToApp()` guards `isFinishing`/`isDestroyed` and reports a message instead of finishing when a screen
  switch fails;
- a static release gate now fails the build if `web.` ever reappears inside the interception block.

## 2. Tapping a control no longer leaves the app

"Open externally" used to launch another app immediately. It now asks for confirmation and explains that the
StreamDock session stays in the app. It also moved from the toolbar into the browser options menu.

## 3. Fewer buttons (the extra ones are gone)

- Browser toolbar is back to four buttons: **Back / Forward / Reload / Download**. The separate video button,
  the "Use page in native downloader" button and the "Open externally" button were removed; their actions live in
  the browser options menu.
- The browser's **Download** button now always does something visible: it lists captured media when there is any,
  or explains that the page has not loaded media yet and offers "Scan again" / "Send page to Home".
- The browser navigation bar now highlights **Browser** (it previously highlighted the Tera tab and the Tera entry
  did nothing).
- Home: the three third-party helper tiles (TeraSaver, TeraPlayer, TeraBoxDL) are removed. The remaining
  **TeraBox** tile opens the native Tera engine instead of the website.
- Home: the duplicate "Open the TeraBox engine" button is gone (the bottom bar and the quick tile already do it).
- Tera tab: the resolver field is now hidden behind one **Resolver settings** button.

## 4. What was not changed

NewPipe-first extraction, yt-dlp fallback, Media3 preview, MP4/WEBM/MP3/M4A saving, local FFmpeg, the
Android 9 URL-codec fix, the FFmpeg runtime layout, the selected SD launcher icon and the TeraBox engine
(listing, honest verification/download-link reporting, optional resolver) are untouched.

## 5. Still true, honestly

- TeraBox **original-file** download links are still not issued to an anonymous session for the sample share; the
  Tera tab reports that plainly, and the operator-resolver option (`docs/RESOLVER-SETUP.md`) remains the supported
  path for that. Media the TeraBox page itself plays can still be captured by the browser's Download button.
- No real-device test of this build was possible here; the fixes are verified by unit tests, lint and the static
  release gates listed in `VERIFICATION.md`.
