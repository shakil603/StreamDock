# StreamDock 1.8.0 — frozen-glass media browser

An **English-language Kotlin Android app** with a frozen-glass interface (frosted slabs, holographic rims, ice-blue accent, chrome wordmark), a built-in HTTPS browser, selectable web search, original-file browser downloads, and the proven NewPipe-first / local FFmpeg media workflow.

**Current APK:** ARM64 debug/test build, `org.streamdock.app`, versionCode **20**, versionName **1.8.0**. Same signing certificate as previously supplied builds. Android 7+ is the declared minimum; the user's known device is Symphony Z25 / Android 9 / ARM64.

## TeraBox share engine (1.3.0)

The **Tera** tab resolves a public TeraBox share link inside the app and offers **Play** / **Save** per file — no account password, cookie import or third-party downloader website. Sharing a TeraBox link to StreamDock opens that tab directly. Files are listed first-party; if TeraBox does not issue a download link for the share, the app says so instead of pretending success, and an optional operator resolver origin can be configured under *Advanced* (see `docs/TERABOX-ENGINE-1.3.0.md`).


## What changed in 1.8.0

- **TeraBox videos play inside the app.** No browser step, no external player: a first-party ladder (WAP page-state dlinks → `/share/download` mint → `/share/streaming` HLS manifest) plus one in-app check sheet that completes TeraBox's own anti-bot step when it is demanded — then Play/Save retry automatically. Details: `docs/TERABOX-INAPP-1.8.0.md`.
- The "open it in the browser" error loop is gone from the TeraBox flow.

## What changed in 1.7.0

- **Real website icons:** Quick Access tiles show the actual site marks (YouTube, SoundCloud, TeraBox…) loaded from bundled brand icons or the sites' own favicons.
- **One player interface:** video and audio share one screen with audio-only mode (NewPipe style), background playback with a media notification, ±10s, queue next/prev, repeat, speed, lock, rotate, brightness/volume gestures.
- **A browser that remembers:** tabs, saved browsing sessions (restored after restart), history, bookmarks, find on page, desktop site, text size, share. Details: `docs/PLAYER-BROWSER-1.7.0.md`.

## What changed in 1.6.0

- **New visual language: “Frozen Glass.”** The rejected cinematic theme is gone. Every
  surface is now a frosted glass slab with a holographic rim and a coloured shadow, over a
  silver aurora backdrop; ice-blue gradient progress; chrome “StreamDock” wordmark; Manrope
  typography bundled with the app. Launcher icon rebuilt in the same language.
- Same five screens, same download engine, same bottom bar (structure, height, labels and
  routing unchanged — only the surface is glass). Details: `docs/FROZEN-GLASS-1.6.0.md`.

## What changed in 1.5.0

- **The version is finally visible:** the Tools footer used to be a hard-coded "1.4.0", so every newer build looked identical on the phone. It now reads the installed version at runtime (**Tools → "STREAMDOCK / 1.5.0"**), and versionName moved to 1.5.0.
- **TeraBox:** a three-step card on share pages, and the page's own Download button now saves through the in-app engine (page Referer kept, pause/resume, container verification) instead of Android's plain download service. Details: `docs/TERABOX-AND-VERSION-1.5.0.md`.

- **"Downloaded fine but the video is ruined" — fixed (code 16):** the app now sniffs the real container of every finished file (MP4 vs M4A brands, Matroska/WebM, TS, MP3/FLAC/OGG/WAV, HTML/playlist), merges DASH tracks only into codec-safe containers (MP4 for H.264/HEVC+AAC, otherwise Matroska with a stated notice), trusts the container yt-dlp/NewPipe actually produced (with a size-verified, synced export and a document rename to the true extension), and refuses parallel ranges on windowed CDN URLs such as Facebook's `bytestart/byteend` links. Details: `docs/MEDIA-INTEGRITY-1.4.0.md`.
- **Design (code 16):** the TERABOX SHARE card is gone from Home; quick-access tiles are numbered scenes; Downloads shows a condensed ACTIVE / PAUSED / FINISHED stat strip. Bottom bar untouched.

- **Real Chromium, directly (code 15):** a Chromium button in the browser toolbar, a default-engine switch that sends every page straight to Chrome (Custom Tabs = full engine, your own session), a second button on the detected TeraBox card, and a chooser on the Tera tab that explains the difference. Nothing is handed to Chrome (no cookies/headers/bridges — gated). Trade-off stated in-line: what Chrome plays cannot be captured for in-app saving.
- **Cinematic redesign (code 15), bottom bar untouched:** condensed all-caps display type with poster tracking, red title-card kickers on every section, page title bars with hairline rules, poster-style buttons (compact 44 dp kept), and a soft three-stop backdrop instead of flat panels. Details: `docs/CINEMA-CHROMIUM-1.4.0.md`.

- **TeraBox verification (400310) — fully diagnosed, two honest paths:** a browser-identical session (real cookies + `sign` + `timestamp` + `jsToken`) is refused with `need verify_v2` on every TeraBox origin, so no anonymous client can mint the link. The app now (a) detects TeraBox's own verification step inside the built-in browser and guides you through it — the page plays, and the captured HLS stream is saved by the in-app engine — and (b) **ships the MIT resolver service** (`backend/terabox-api/`, deploy recipe in `backend/README-STREAMDOCK.md`) whose own verified session makes native Play/Save work. Resolver errors are reported by code. Evidence: `docs/TERABOX-VERIFY-1.4.0.md`.

- **TeraBox verification (400310) handled honestly + a path that plays:** the Tera tab now has a **Browser** button per file (and "Open share in browser"). TeraBox refuses to mint a public direct link for verification-required files for anonymous sessions, so instead of a dead end the share opens in the built-in Chromium browser, where the page's own player works — and the in-app engine can now **save real HLS streams** (best variant → segments appended in order, pause/resume at segment boundaries, DRM refused). Details: `docs/UI-AND-TERA-1.4.0.md`.
- **Phone-fit UI:** everything scaled ~20–25 % down, the system font scale is clamped to 100 % for the app, and the bottom bar labels are single-line auto-sized, so nothing wraps or overflows on a 720-wide phone. Details: `docs/UI-AND-TERA-1.4.0.md`.

- **TeraBox analysis fixed:** the page reader demanded a fixed 2 MB and threw `EOFException` for every normal share — now fixed, with automatic retry, HTTP/1.1 fallback and mirror-host fallback. Verified live: both screenshot shares list their files. Details: `docs/TERA-FIX-1.4.0.md`.
- **Chromium options:** browser options can open the page in Chrome and show/update the Chromium engine Android uses.
- **In-app download engine:** several files download at once, each with optional multi-connection transfer; Pause / Resume / Cancel / Restart per file, speed display, automatic retry after connection loss and automatic continuation after app restart. Details: `docs/DOWNLOADS-1.4.0.md`.
- **Fixed the 1.4.0 crash** where browsing (or tapping browser options) closed the app: the media observer no longer calls WebView methods from its background thread. Extra buttons were removed and the browser Download button now always responds. Details: `docs/FIXES-1.4.0.md`.
- **Browser media capture:** while you browse any site, StreamDock lists the media the page itself loads (files, HLS/DASH manifests, audio) under the new video button — then **plays it in the app or saves it** with the same User-Agent/Referer the page used. No page cookies are ever attached to a download, and observation never modifies a request.
- **TeraBox inside the browser:** opening a TeraBox share page shows an "Open in the TeraBox engine" panel, and a paste/share of a TeraBox link now resolves immediately with no further taps.
- **Operator resolver setup** documented in `docs/RESOLVER-SETUP.md` with `tools/tera-resolver-smoke.sh` for end-to-end verification (metadata contract + real media bytes).

## What changed in 1.3.0

- New **Tera** tab: public TeraBox share link → file list → Play / Save, with honest reporting when TeraBox does not issue a download link.
- Selected cinematic black/red **SD icon** integrated as the launcher icon (legacy, round and adaptive variants).
- Bottom navigation is now Home / Tera / Browser / Downloads / Tools; pasted or shared TeraBox links route to the Tera tab.

## What changed

- **Home · Browser · Downloads · Tools** navigation, native vector icons, an original cinematic banner and platform shortcuts.
- All app-owned Kotlin labels, progress text and errors are English. AppCompat requests English; websites, filenames/media titles and Android system dialogs remain under their own language settings.
- Browser address/search bar with **Google / DuckDuckGo / Bing**, back/forward, reload/stop, fullscreen website video, external-open fallback and cookie/cache clearing.
- Official TeraBox plus **TeraSaver, TeraPlayer and TeraBoxDL** website shortcuts. Third-party web tools are labeled and require a visit confirmation; the app never automatically pastes a share link or exports account cookies into them.
- Browser file links are confirmed, then handed to **Android DownloadManager** with no Cookie/Authorization headers. Files go to the public **Downloads** folder. Status/open/share/cancel/delete controls are in Downloads.
- **Native TeraBox account login/import/file browsing was removed by explicit user choice.** Old tagged native TeraBox tasks are cancelled and its encrypted session/job directory and Keystore alias are retired on upgrade. Previously exported media is not deleted.

No Spring Boot/Bun backend, proxy server, shared cookies or third-party downloader script is bundled.

## Using the app

### Browse and search

1. Use Home's search field or the Browser tab.
2. Pick a search provider at the top of Browser. Type a search phrase or HTTPS address.
3. Use the toolbar for navigation, native-downloader handoff or external opening.
4. The settings/sliders button contains browser options, privacy notes and website-data clearing.

Known typed `http://` addresses are upgraded to HTTPS; cleartext browsing, invalid certificates and local/app URI schemes are blocked. If a site cannot work within these constraints, explicitly open it externally.

### TeraBox (website-only)

Open the official site to browse/sign in, or manually visit a third-party public-share tool. Paste a permitted share link yourself. Website availability, login policy, generated links, quality and premium/rate restrictions belong to that website. StreamDock does not promise that any of these sites can unlock/download every share.

There is **no native private-file API engine, cookie import or account-session export** in 1.2.0. Some sites refuse embedded browser login; use the external-open button. External browsers use separate sessions.

### Browser files

When a website emits a direct HTTPS file download, review the confirmation. Android DownloadManager receives the file URL, User-Agent and only the page's origin as Referer—not cookies, credentials or the full private page URL. A signed file URL itself may carry temporary authorization and is handled/stored by Android's download service as part of the job.

- **Android 7–9:** allow storage permission when asked to save browser files into public Downloads. This permission is declared only through API 28.
- **Android 10+:** DownloadManager's public Downloads path needs no legacy storage permission.
- Original filenames are sanitized and byte-bounded; a short unique prefix avoids overwriting an existing file. Original extensions are kept where valid.
- DownloadManager controls scheduling, retries, progress and file storage. It does not guarantee the downloaded bytes are a valid video. A status of Saved means Android completed that transfer.
- Login-cookie-dependent, blob, DRM and app-specific downloads may fail or require an external browser. Website streaming/manifests are not automatically extracted or merged by this browser.
- Browser files can run under Android's scheduling independently of the single native transfer. Native downloads still use SAF and do not require the new legacy browser-storage permission.

### Native media downloads and local conversion

Home → scroll to **Download a link** → **Analyze link** → choose preview, container/quality → **Download & save**.

- Supported NewPipe service → fresh progressive HTTPS URLs → validated direct transfer → optional local FFmpeg merge/audio conversion → SAF export.
- Only unsupported service lookup chooses yt-dlp for download. A supported NewPipe 403/429 never silently switches engines or retries.
- NewPipe live/HLS/DASH-manifest-only transfers remain unsupported. Separate progressive video/audio tracks are supported.
- Main analysis retains its existing fallback behavior; Tools offers direct yt-dlp analysis and its updater.
- Tools → **Local Studio** → choose a local video → MP3/M4A → convert/save.
- Native runtimes retain the 1.0.4 shared/Python archive → FFmpeg archive → ELF checks → executable preflight sequence. This is not yt-dlp URL extraction for NewPipe media.

The Android 9 NewPipe URL-codec fix and the entire editable `newpipe-extractor/` module remain unchanged. Core download logic was preserved; app messages were translated to English. See the preservation evidence in `docs/tests-1.2.0/`.

## Security / limitations

- HTTPS-only embedded navigation, certificate errors cancelled, mixed content blocked, file/content access off, no native JavaScript bridge, third-party cookies off, camera/microphone/location denied, Safe Browsing enabled where supported.
- JavaScript and first-party cookies are enabled because ordinary websites require them. WebView stores its own site data on-device; this is **not** the retired AES-GCM TeraBox vault and is not claimed to be a production audited credential store.
- The browser does not maintain a separate app-owned browsing-history database. WebView navigation state exists during the Activity; website cookies/storage may persist until cleared. Search queries go to the chosen provider. Websites can contact their normal third-party resources and show ads.
- Clear website data calls Android's cookie, web-storage, cache and history APIs. It does not revoke sessions on other devices or remove downloads.
- Downloads can fail due to permissions, storage, Android battery/background policy, expired URLs, site restrictions or service changes. Use only your own or authorized media. No DRM/paywall/CAPTCHA bypass.

## Verification

See **[docs/VERIFICATION.md](docs/VERIFICATION.md)** for exact final results, hashes and evidence. **No phone/emulator UI, live WebView login, Android DownloadManager permission flow or real TeraBox download was executed here.** The user's confirmation that 1.0.4 worked is historical, not a device-validation claim for the redesign.

Initial all-in-one packaging exceeded this sandbox's Gradle memory budget. Tests/lint and assembly were then run separately; the final independent tasks passed. On a similarly small machine:

```sh
chmod +x gradlew
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx650m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' \
  -Pkotlin.compiler.execution.strategy=in-process -PtargetAbi=arm64-v8a \
  newpipe-extractor:test testDebugUnitTest lintDebug
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx650m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC' \
  -Pkotlin.compiler.execution.strategy=in-process -PtargetAbi=arm64-v8a assembleDebug
python3 tools/verify_url_codec_dex.py app/build/outputs/apk/debug/app-debug.apk
python3 tools/verify_ffmpeg_runtime.py app/build/outputs/apk/debug/app-debug.apk
python3 tools/verify_browser_release.py app/build/outputs/apk/debug/app-debug.apk
```

Use Java 17, Android SDK platforms 35 and 28, build-tools 35.0.0. Gradle wrapper 8.11.1 / AGP 8.9.1 / Kotlin 2.1.20 are pinned. The GitHub Actions workflow includes the checks but has **not** been run remotely. Other ABIs/universal APKs were not locally tested. CI generates a different debug key unless you configure signing; do not expect an arbitrary CI APK to update the supplied signed APK.

## Sources and licensing

Current application source: **GPL-3.0-or-later**. Full editable app/browser/NewPipe code and build scripts are included. See `THIRD_PARTY_NOTICES.md` for component licenses and native FFmpeg/Python corresponding-source obligations; this ZIP is not a complete source bundle of all embedded native dependencies.

Archived 1.1.0 docs describe a now-retired AGPL TeraBox integration. Do not use them as current app instructions. See **[docs/BROWSER-1.2.0.md](docs/BROWSER-1.2.0.md)** for migration and phone acceptance checks.
