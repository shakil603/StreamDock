# StreamDock 1.2.0 — final verification (2026-10-10)

## Scope

Cinematic English UI, a general HTTPS WebView browser, selectable search, original-file Android DownloadManager integration and **browser-only TeraBox**. The user explicitly chose to remove the native TeraBox adapter/private-account importer. NewPipe/FFmpeg logic remains based on the phone-confirmed 1.0.4 baseline.

**No Android phone/emulator execution was performed for this redesign.** Live WebView rendering, OAuth/login, website fullscreen, Android 9 permission prompts, DownloadManager downloads, upgrade cleanup and native playback/conversion in the redesigned screens still require device testing. Inspecting public landing pages is not a successful download test.

## Final checks

| Check | Result |
|---|---|
| App JVM suite | **62 passed**, 1 optional live NewPipe probe skipped |
| NewPipe source-module suite | **11 passed** |
| Total | **73 passed**, 0 failures/errors, 1 skipped |
| New browser policy tests | **17 passed** |
| Lint | **0 errors, 69 warnings** |
| ARM64 APK assembly | Passed, versionCode **7**, versionName **1.2.0** |
| APK signature | v2 verified, same certificate as prior delivered APKs |
| DEX URL-codec audit | Passed; no `URLDecoder/URLEncoder` Charset-overload references |
| Native closure/ABI audit | **79 ARM64 ELF files**, non-system dependency availability passed |
| Browser release structural audit | Passed: English app Kotlin strings, no old TeraBox classes, browser security settings, no cookie export, API-limited storage permission, banner asset |
| Core preservation | Eight core files match 1.0.4 exactly after only enumerated English message replacements; some are byte-identical. Worker has those replacements plus rejection of retired `teraJob` input. Entire NewPipe module is byte-identical. |
| GitHub Actions / universal / other ABIs | Workflow updated, not executed remotely; other ABIs not built locally |
| Device / live TeraBox download | **Not run** |

APK size: **67,542,034 bytes**

APK SHA-256: `866e211ef692beb383aa62871e6af34d00016f1000401afa419c0d9647f9d01d`

Certificate SHA-256: `2d3216263b235a504cdb0895aef36c1b0f881dc68fc0f51e14693efa7ec069cc`

The initial combined compile/test/lint/package attempt exhausted the sandbox daemon's memory budget at packaging. No APK from that failed attempt was delivered. Splitting tests/lint from assembly produced successful final runs, recorded in `tests-1.2.0/tests-lint-build.txt` and `tests-1.2.0/build.txt`.

## What is tested

The pure browser-policy suite covers selected search-provider routing and encoding, explicit HTTP-to-HTTPS input upgrading, unsafe schemes/userinfo rejection, strict helper-domain matching, browser-only TeraBox routing, origin-only Referer construction, traversal-safe filenames, UTF-8 byte limits, preserved extensions and Unicode combining marks.

Existing tests continue to cover selectors, Android 9 codec compatibility, native runtime orchestration and validated HTTPS ranges/cancellation/403/429/truncation behavior. These do not exercise Android DownloadManager, which has a separate system-managed retry/redirect/storage behavior.

The structural release script checks actual source plus final APK DEX/asset presence. This is **not** a penetration test or proof that a site supports WebView. JavaScript is intentionally enabled for normal browsing with file/content access disabled, mixed content blocked, no native bridge and TLS errors cancelled; the deliberate SetJavaScriptEnabled lint suppression is not a claim that arbitrary web content is trusted.

## Evidence and reproduction

- `tests-1.2.0/app/` and `extractor/`: exact JUnit XML.
- `tests-1.2.0/lint.xml`: final warnings; not a lint-clean claim.
- `tests-1.2.0/core-preservation.json`: baseline comparison checks and hashes.
- `tests-1.2.0/url-codec-audit.txt`, `native-runtime-audit.txt`, `browser-audit.txt`, `signature.txt`: final artifact audits.
- Current README: toolchain and two-stage build commands.
- `BROWSER-1.2.0.md`: outstanding device acceptance checklist and migration scope.

Previously supplied 1.0.4 and 1.1.0 source archives are preserved separately under `deliverables/stable-source/`. Prior verification documents are historical, not current guarantees. This is an ARM64 debug/test release, not a production security audit or a claim of unrestricted downloads.

**1.3.0:** see `TERABOX-ENGINE-1.3.0.md` — in-app TeraBox engine, 86 unit tests (1 optional-live skipped), lint 0 errors, URL-codec/FFmpeg-ABI/release-boundary audits passed on the ARM64 debug APK. Real-device and live-download validation remain outstanding.

**1.4.0:** see `BROWSER-1.4.0.md` — browser media capture (any platform) with in-app Play/honest Save, TeraBox share detection inside the browser, one-tap TeraBox analyze, operator resolver setup (`RESOLVER-SETUP.md`, `tools/tera-resolver-smoke.sh`). 93 unit tests (1 optional-live skipped), lint 0 errors, URL-codec/FFmpeg-ABI/release-boundary audits passed. Real-device validation outstanding.

**1.4.0 (versionCode 10):** crash fix (no WebView access from the interception thread), control cleanup and navigation fixes — see `FIXES-1.4.0.md`. 93 unit tests (1 optional-live skipped), lint 0 errors, URL-codec/FFmpeg-ABI/release-boundary audits passed on the packaged APK.

**1.4.0 (versionCode 11):** in-app download engine — several files at once, per-file pause/resume, segmented multi-connection transfer, automatic retry and restart recovery; see `DOWNLOADS-1.4.0.md`. Unit tests now include the planner/queue arithmetic.

**1.8.0 (versionCode 20):** TeraBox fully in-app — first-party link ladder (WAP `__INITIAL_STATE__` dlinks, `/share/download` POST+GET mint with tplconfig/shorturlinfo/WAP signature hydration, `/share/streaming` HLS manifest with `fid`), in-app TeraBox check sheet (`TeraCheckView`) with host-scoped check-cookie vault (`TeraCookies`), automatic Play/Save retry; all external-browser offers removed from the TeraBox flow — `docs/TERABOX-INAPP-1.8.0.md`.

**1.7.0 (versionCode 19):** unified player (`player/PlayerActivity` + `player/PlaybackService` MediaSessionService: audio-only mode, background media notification, ±10s/queue/repeat/speed/lock/rotate/gestures), real site icons (`SiteIcons.kt` + bundled brand marks), browser tabs with persisted sessions, history, bookmarks, find-on-page, desktop site, text zoom, share — `docs/PLAYER-BROWSER-1.7.0.md`.

**1.6.0 (versionCode 18):** frozen-glass redesign — `DockVisuals.kt` (GlassSurface, AuroraBackdrop, ChromeText, GlassProgress, depth/press), rewritten shared `CinemaUi` kit, bundled Manrope faces, generated hero art and launcher icon, light system chrome; no engine or downloader change — `docs/FROZEN-GLASS-1.6.0.md`.

**1.5.0 (versionCode 17):** version name finally visible on the phone (footer reads the installed version; the old hard-coded "1.4.0" string was why new builds looked identical), TeraBox share pages show a three-step card, and TeraBox/Baidu CDN links produced by the page's own Download button now route into the in-app download engine with the page as Referer (pause/resume + container verification) — `docs/TERABOX-AND-VERSION-1.5.0.md`.

**1.4.0 (versionCode 16):** media-integrity fix for "the video downloads fine but is ruined": container sniffing before any success claim (`MediaContainerReader`), codec-safe FFmpeg merge (MP4 only for MP4-native codecs, otherwise Matroska + stated), trust the container yt-dlp/NewPipe actually produced with size-verified synced export and document rename, and single-connection enforcement for windowed CDN URLs (Facebook `bytestart/byteend`, `fbcdn`, chunk paths) plus an explicit `Accept-Ranges` requirement. Home's TERABOX SHARE card removed; quick tiles and Downloads stat strip redesigned — `docs/MEDIA-INTEGRITY-1.4.0.md`.

**1.4.0 (versionCode 15):** cinematic redesign outside the (untouched) bottom navigation — condensed all-caps display type, red scene kickers, title bars, poster buttons kept at the compact 44 dp, soft backdrop; and **real Chromium used directly** via Chrome Custom Tabs with a default-engine switch and a Tera-side chooser, performing no cookie/header/bridge hand-over. New gate `tools/verify_cinema_chromium.py` — `docs/CINEMA-CHROMIUM-1.4.0.md`.

**1.4.0 (versionCode 14):** TeraBox `need verify_v2` (400310) evidenced against a browser-identical session on all five origins; browser now detects and guides TeraBox's own verification step; **MIT resolver service vendored** at `backend/terabox-api/` with a free deploy recipe; resolver input accepts a bare `*.workers.dev` host and resolver errors are reported by code — `docs/TERABOX-VERIFY-1.4.0.md`.

**1.4.0 (versionCode 13):** TeraBox verification (400310) now answered honestly *and* actionably — per-file **Browser** button opens the share in the built-in Chromium browser; the in-app download engine gained a real **HLS/stream saver** (best variant → segments appended in order, DRM refused, resume at segment boundaries). UI scaled ~20–25 % down, system font scale clamped to 100 %, bottom-bar labels single-line/auto-sized (no more wrapping). Evidence: live HLS test 3 segments → 25,004,000 bytes; 116 tests / 3 skipped / 0 failed — `UI-AND-TERA-1.4.0.md`.

**1.4.0 (versionCode 12):** TeraBox analysis fixed (`readByteArray`/EOF bug) + transport retry, HTTP/1.1 fallback, mirror hosts; Chromium options in the browser — `TERA-FIX-1.4.0.md`. Live check: both screenshot shares now list their files; download links are still refused to anonymous sessions (errno 2/400310).
