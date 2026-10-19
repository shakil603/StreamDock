# Third-party notices — 1.2.0

Current application source: **GPL-3.0-or-later** (`LICENSE`). The native AGPL TeraBox module and its tests were removed in 1.2.0 at the user's request. No OpenList code is compiled into this APK. `LICENSE-AGPL-3.0` and the archived 1.1.0 documents remain as historical licensing records, not as a claim that the retired engine is still present. Third-party components retain their original licenses.

This is an engineering inventory, not an exhaustive legal audit of all transitive/native components.

## Direct components

| Component | Pinned version | Upstream / license family |
|---|---|---|
| NewPipeExtractor | local v0.26.5 + Android URL-codec compatibility patch | https://github.com/TeamNewPipe/NewPipeExtractor — GPL-3.0-or-later |
| youtubedl-android library, ffmpeg | 0.18.1 | https://github.com/yausername/youtubedl-android — GPL-3.0; includes separately licensed components |
| yt-dlp | embedded/updateable | https://github.com/yt-dlp/yt-dlp — consult exact bundled distribution and optional dependencies |
| FFmpeg | embedded by wrapper | https://ffmpeg.org/legal.html — LGPL/GPL depending on configure options and linked codecs |
| Python | embedded by wrapper | https://docs.python.org/3/license.html — PSF and included-component notices |
| AndroidX / Media3 / WorkManager | see app/build.gradle | https://android.googlesource.com/platform/frameworks/support/ — predominantly Apache-2.0 |
| Material Components Android | 1.12.0 | https://github.com/material-components/material-components-android — Apache-2.0 |
| OkHttp | 4.12.0 | https://github.com/square/okhttp — Apache-2.0 |
| Kotlin | 2.1.20 | https://github.com/JetBrains/kotlin — Apache-2.0 |
| Gradle wrapper | 8.11.1 | https://github.com/gradle/gradle — Apache-2.0 |
| JUnit | 4.13.2, tests only | https://github.com/junit-team/junit4 — EPL-1.0 |

No claim of affiliation with NewPipe, yt-dlp, FFmpeg or media platforms is made.

## Binary redistribution checklist

Before public distribution, inventory **all transitive and embedded native components**, preserve their license/NOTICE files, and supply the precise corresponding sources and build scripts required by their licenses. An upstream link alone may not satisfy source-distribution obligations. This generated source archive is not a complete corresponding-source bundle for all embedded third-party native libraries.

The wrapper consumes prebuilt native archives; rebuilding the application does not rebuild FFmpeg/Python. See:

- https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md
- https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md
- https://github.com/yausername/ffmpeg-android-maker

The source project deliberately does **not** add `ffmpeg-android-maker` as an Android runtime dependency: it is build tooling. The runtime dependency is the wrapper's published `ffmpeg` AAR.

Review codec/patent obligations and app-store policy separately where applicable. There are no account credentials, session cookies or downloaded movie/video files bundled in this application. The original generated banner is listed below.

## Editable NewPipe source in 1.0.3

The `newpipe-extractor/` module replaces (does not duplicate) the original binary dependency. All 359 upstream Java files were imported from the pinned v0.26.5 source archive; only `Utils.java` was modified to use Android-compatible URL encode/decode overloads. `newpipe-extractor/UPSTREAM.md`, `UPSTREAM.pom`, `LICENSE`, retained proto schemas and `android-url-codec.patch` document provenance, dependencies and changes. This is a local fork, not an official upstream release.


## Browser and design

- Android System WebView and Android DownloadManager are device-provided services. They are not embedded copies of Chrome or a hosted proxy.
- Google, DuckDuckGo, Bing, YouTube, SoundCloud, TeraBox, TeraSaver, TeraPlayer and TeraBoxDL links are navigation shortcuts. Their websites have their own terms, privacy policies, advertisements and availability. There is no endorsement or partnership.
- No code from `Ronnie7866/terabox-downloader-online`, `abhinai2244/TeraBox-Dl` or `eyeblech/tera-dl` is bundled. The repositories were inspected as references; the user chose a browser-only TeraBox workflow instead of another API/CLI engine.
- `cinema-hero.jpg` was generated for this project's original cinematic design. It is not a movie still and contains no Marvel characters/logos. Icons are native path drawings; no external font/CDN is required by the app chrome.
- The current application no longer needs the 1.1.0 test-only `org.json:json` dependency.

## Paidguy/terabox-api (reference for first-party TeraBox API flow)

- Upstream: https://github.com/Paidguy/terabox-api — MIT, inspected revision `4d3149a22888018375a933d1c0c0ebc3eef32fb6`.
- StreamDock's `TeraEngine`/`TeraTransfer` are original Kotlin code; upstream was not compiled, linked or bundled. The reference informed which first-party endpoints and parameters to implement (share listing variants, the `/api/shorturlinfo` and WAP share-record lookups, the `/share/download` request shape, and honest mapping of verification errors).
- The upstream clone under `backend/terabox-api/` is a local review copy and is excluded from the delivered source archive.

