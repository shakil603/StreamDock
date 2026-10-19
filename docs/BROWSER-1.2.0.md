# 1.2.0 browser-only TeraBox redesign

## Explicit user choices

The user requested simpler browser/search workflows, an all-English app UI and a cinematic dark/red visual direction. They selected **browser-only TeraBox** instead of keeping the native adapter hidden under Advanced Tools. They selected cinematic styling rather than a light marble texture or a plain VidMate-like layout.

The app uses its own StreamDock branding, original generated scenery and native icon paths. No Marvel logo, movie still or character art is used.

## Retired feature migration

- Removed `app/.../terabox/`, its tests, Activity registration and native Worker branch.
- Main startup cancels WorkManager jobs tagged `terabox`, deletes only `noBackupFilesDir/terabox`, deletes only `streamdock.terabox.v1` from Android Keystore and clears that old Activity's preferences. Failed cleanup is retried on the next launch.
- Current `TransferWorker` rejects leftover `teraJob` inputs rather than sending them to another engine.
- Existing native NewPipe/local jobs and exported media are not removed. WebView does not import the retired account cookies; sign in to a website yourself if needed.
- The legacy source ZIP is kept separately for history. The APK contains no `org.streamdock.app.terabox` classes.

## Supplied references: how they were used

Inspected public repository pages and website descriptions, without accessing their credentials or running their scripts:

- https://github.com/Ronnie7866/terabox-downloader-online — describes a Spring Boot API and links to teraboxdl.site; mentions cookie rotation. Not bundled.
- https://github.com/abhinai2244/TeraBox-Dl — describes a Bun/TypeScript server and Python wrapper. Not bundled; no `.env` or hosted credentials imported.
- https://github.com/eyeblech/tera-dl — a Python CLI whose README requires an `ndus` cookie and uses another API/proxy. Not bundled.
- https://terasaver.com/ and https://www.teraplayer.in/ — website shortcuts only. Vendor claims such as unlimited/free/safe are not verified guarantees from StreamDock.

Opening a shortcut makes ordinary website requests. Pasting a share into a third-party tool is the user's explicit action and subject to that site's policies. The app never appends an account cookie or the user's current share URL to a helper shortcut.

## Browser boundaries

The browser is a single Android WebView, not a full Chrome replacement. It supports navigation/search, website media fullscreen and explicit external opening. No tab manager, cookie export, upload picker, camera/mic/location access or universal media sniffing is provided. Certain login providers reject embedded WebViews. Automatic popups are disabled; external-app URI schemes are blocked.

Only explicit direct HTTPS file download events are delegated to Android DownloadManager, after confirmation and any required API 24–28 storage permission. No raw Cookie/Authorization header is forwarded. That avoids handing a session credential to a redirect chain the app does not control, but intentionally means some authenticated file links will fail. The app does not claim that DownloadManager validates media containers, enforces its own HTTP redirect policy, or removes server throttles. Use the website/external browser for those cases.

## Acceptance checklist — still needs device testing

- [ ] Install over 1.0.4/1.1.0 on Symphony Z25; confirm version 1.2.0 and English UI.
- [ ] Home shortcuts, bottom navigation, native preview and output controls fit at default and enlarged font sizes.
- [ ] Search Google/DuckDuckGo/Bing; visit HTTPS pages; back/forward/reload/stop.
- [ ] Website video fullscreen enters/exits; rotate browser; return/back from external app.
- [ ] Helper notice appears; manual permitted public share works if the service supports it.
- [ ] Direct file download asks for confirmation and Android 9 storage permission; denial is handled.
- [ ] Android DownloadManager progress, saved file, open/share/delete and Downloads-folder location.
- [ ] Cookie-dependent/blob/unsupported cases show a usable external path without session export.
- [ ] Clear website data; verify intended site's sign-in state; no downloaded media deleted.
- [ ] Old native TeraBox jobs/session are retired on upgrade; no imported cookies appear in WebView.
- [ ] Previously working NewPipe high-quality merge, MP3/M4A and Local Studio still work on the phone.

Build/JVM/static checks do not fill in these boxes. Do not send cookies/passwords for troubleshooting. Report the version, the affected screen, a non-sensitive public example if appropriate, and the visible error.
