# StreamDock 1.1.0 — verification, 2026-10-10

## Status and scope

**TeraBox is experimental: implementation + local HTTPS fixture tests, not a live-service success claim.** No real public share/account, Android UI, Android Keystore operation or new-feature device conversion was tested here. The user confirmed 1.0.4 worked on their Symphony Z25 / Android 9; do not transfer that confirmation to TeraBox 1.1.0.

New feature: OpenList official account-flow Kotlin adaptation + separate public-share adapter; local encrypted session import, folder pagination, fresh link resolution, Original/MP3/M4A transfer and SAF output. No proxy/premium bypass. See [feature guide](TERABOX-1.1.0.md).

## Final results

| Check | Result |
|---|---|
| App JVM tests | **78 passed**, 1 optional NewPipe live probe skipped |
| Local NewPipe extractor tests | **11 passed** |
| Combined | **89 passed**, 0 failures/errors, 1 skipped |
| New TeraBox tests | **33 passed** (17 policy/crypto + 16 HTTPS client fixtures) |
| Android lint | **0 errors, 43 warnings**; KTX/i18n and prior-project warnings, not silenced |
| ARM64 assembly | Passed, `org.streamdock.app`, versionCode **6**, versionName **1.1.0**, minSdk 24 |
| Signing | v2 verified; same certificate as supplied 1.0.4 |
| Final-APK URL codec audit | Passed; String-encoding-name overloads, no unsupported Charset overload references |
| Final-APK native runtime audit | **79 ARM64 ELF files**; non-system dependency closure/ABI available |
| Existing core preservation | Seven central Kotlin files and the entire vendored NewPipe module byte-for-byte unchanged against delivered 1.0.4 source |
| GitHub Actions / other ABIs | Workflow remains; not run remotely, other ABIs not built here |
| Real TeraBox / phone | **Not run** |

Final APK: **67,440,238 bytes**

SHA-256: `24a700290e260c813db66537384c1a745d679327237410cef450752a169d9ee5`

Signing certificate SHA-256: `2d3216263b235a504cdb0895aef36c1b0f881dc68fc0f51e14693efa7ec069cc`

## What the TeraBox tests establish

- Known share URL normalization/alias handling, no lookalike domains, HTTPS/userinfo guards, no injected file IDs, exact >2^53 identifier preservation.
- Account pagination parameters and own-session prerequisite; public listing; share-code POST and scoped verification cookie; selection of the requested file and a newly returned link.
- Official account signature known vector and request shape, with no `vip` override.
- No cookie to an unrelated host after an actual HTTPS redirect; original host/path-only scope retained; no cleartext cookie dispatch.
- Immediate 429/CAPTCHA stop, bounded token refresh, rejected malicious region hints, no fabricated link or fallback when dlink is missing.
- Malformed cookie/API/verification error messages do not echo fixture secrets; diagnostics redact multiple secrets on one line.
- AES-GCM round trip, distinct nonce outputs, ciphertext tamper rejection and filename/purpose binding on JVM. **This does not test Android Keystore hardware/provider behavior.**
- A cancelled API client cannot start a subsequent authenticated request.

Fixture credentials are synthetic and are never used against TeraBox servers. Fixtures verify implemented protocol behavior, **not that a current live TeraBox server will accept it**. The account request sequence is adapted from pinned OpenList source; public-password behavior still needs an authorized live acceptance test.

## Preserved baseline

`NativeRuntime.kt`, `DockApp.kt`, `NewPipeTransfer.kt`, `NativeMediaProcessor.kt`, `DirectHttpDownloader.kt`, `ExtractorRepository.kt`, `Models.kt` and the entire `newpipe-extractor/` module match the old source archive. `MainActivity` adds routing/button/version text and safe state handling. `TransferWorker` adds an isolated TeraBox branch and cancellation/cleanup; its original transfer helpers are unchanged. Diagnostic redaction now handles additional credential keys.

## Reproduction

Java 17, SDK platforms 35 and 28, build-tools 35.0.0, Gradle wrapper 8.11.1:

```sh
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx700m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -PtargetAbi=arm64-v8a \
  newpipe-extractor:test testDebugUnitTest lintDebug assembleDebug
python3 tools/verify_url_codec_dex.py app/build/outputs/apk/debug/app-debug.apk
python3 tools/verify_ffmpeg_runtime.py app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Evidence: [`tests-1.1.0/`](tests-1.1.0/) contains final JUnit XML, lint XML, build log, signing and APK audit output, and preserved-core hashes. OpenList reference-file checksums are in [`terabox-upstream/provenance.json`](terabox-upstream/provenance.json). Prior verification is preserved as [`VERIFICATION-1.0.4-HISTORICAL.md`](VERIFICATION-1.0.4-HISTORICAL.md).

This is a debug/test build, not a production security audit or a guarantee of unlimited/available downloads. Binary redistribution also requires the original components' corresponding-source/license obligations described in `THIRD_PARTY_NOTICES.md`.
