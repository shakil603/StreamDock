> HISTORICAL: the native TeraBox adapter was removed in 1.2.0 by user request. See the current README and BROWSER-1.2.0.md.

# Third-party notices

Original StreamDock application source remains **GPL-3.0-or-later** (`LICENSE`). The new TeraBox adaptation in `app/src/main/java/org/streamdock/app/terabox/` is **AGPL-3.0-only** (`LICENSE-AGPL-3.0`). The linked combined work must be distributed in accordance with GPLv3/AGPLv3 section 13; do not describe the whole 1.1.0 application as GPL-only. Third-party components retain their original licenses. This notice is an engineering inventory, not an exhaustive legal audit of every transitive dependency or embedded codec.

## Direct components

| Component | Pinned version | Upstream / license family |
|---|---|---|
| TeraBox account-flow adaptation | OpenList revision `482464c2affc5eeba5d4a25bb272aeb352ec5918` | https://github.com/OpenListTeam/OpenList — AGPL-3.0; read-only Kotlin adaptation, not a bundled Go daemon |
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

Review codec/patent obligations and app-store policy separately where applicable. There are no media assets, credentials, cookies or copyrighted downloaded videos bundled in this application.

## Editable NewPipe source in 1.0.3

The `newpipe-extractor/` module replaces (does not duplicate) the original binary dependency. All 359 upstream Java files were imported from the pinned v0.26.5 source archive; only `Utils.java` was modified to use Android-compatible URL encode/decode overloads. `newpipe-extractor/UPSTREAM.md`, `UPSTREAM.pom`, `LICENSE`, retained proto schemas and `android-url-codec.patch` document provenance, dependencies and changes. This is a local fork, not an official upstream release.

## TeraBox 1.1.0 provenance

- The official account flow and signature operation are adapted from OpenList's `drivers/terabox` (`util.go`, `driver.go`, `types.go`, `meta.go`), copyright their respective contributors. See `docs/terabox-upstream/provenance.json` for the pinned revision. The root `LICENSE-AGPL-3.0` is copied verbatim from that revision.
- Changes: Kotlin/Android port; read-only listing and download only; finite token/region refresh; TLS and cookie-origin enforcement; encrypted Android storage; WorkManager/SAF integration. No write/delete/upload/share-creation API, `linkCrack`, third-party proxy or `vip=2` parameter is included.
- `RrY3999/TeraBox` (MIT) was researched as evidence for the public `/share/list` request shape. Its Python/Windows aria2 implementation is **not bundled or copied**. Public-share/password code is a new adapter, not a claim that OpenList natively supports share links.
- No shared/premium cookies, real account credentials or media are in this distribution. Fixture credentials are visibly synthetic and work only against local HTTPS test servers.
- `org.json:json:20240303` is a **test-only** dependency; the APK uses Android's platform `org.json`. Preserve its upstream license for test-dependency redistribution.
- Existing native-wrapper corresponding-source caveats above continue to apply. This source ZIP contains the entire editable app, TeraBox adaptation, NewPipe fork and build scripts, but not all upstream FFmpeg/Python/native build sources.
