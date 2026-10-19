# TeraBox engine shortlist — source review

**Reviewed:** 2026-10-11. **Scope:** selection before integration, not a working-download certification.

## Decision

1. **Paidguy/terabox-api** is the first candidate for a controlled integration spike. Its repository includes actual first-party extraction, missing/expired download-link resolution, file streaming and HLS handling, rather than only calling an unrelated hosted resolver.
2. **saahiyo-cloud/TeraBridge-api** is a second, more stateful candidate, particularly for quality/HLS exploration. Its download path can copy shared files into the configured operator account. That remote write, storage consumption and cleanup requirement must not be hidden behind a supposedly read-only resolver.
3. Neither is a ready-made Android library, nor has either passed a real media-download test in this review. Both have server-side TeraBox session configuration. That is distinct from requiring the Android end user to import credentials, but does not constitute a credential-free standalone engine.

The desired native experience remains **share link → files/available quality → Play / Download**, without manual end-user account-cookie import. No browser shortcut is being proposed as its replacement.

## 1. Paidguy/terabox-api — first evaluation candidate

- MIT LICENSE verified; last repository push reported by GitHub: **2026-09-21**. Push recency is not evidence of reliability.
- Reviewed resolver: `src/lib/terabox.ts`: signed-list, anonymous-parameter-list and WAP strategies; folder expansion; signature hydration; `freshDownloadLink` uses first-party `/share/download` when a listing lacks a usable dlink.
- Reviewed `src/handlers.ts`: forwards Range, preserves Content-Range, and contains a bounded link-refresh path after upstream 403/410. These are source implementations, not demonstrated successful transfers.
- Reviewed `src/lib/hls.ts`: HLS manifest rewriting. Actual available qualities/container support must be tested; README promises are not codec coverage evidence.
- `src/lib/cookies.ts:37–45` rejects an empty configured session pool. Its “anonymous” fallback therefore must **not** be described as a verified cookie-free deployment.
- Cloudflare Worker runtime, not an Android/Kotlin dependency. A native Android client can use a privately operated instance, subject to deployment approval, privacy and live validation.
- Default service rate limiting exists; `wrangler.toml` documents `RATE_LIMIT=0` to disable its per-IP quota. Platform subrequest/resource limits still exist. Do not promise unlimited operation.
- Verification errors are explicit final failures in the fresh-download path. Nothing reviewed establishes success against the user's previously observed verification response.
- Security controls are present in the design, but this is not a complete security audit. Redirect validation, secret handling, signed URLs and abuse controls require a separate gate.

## 2. saahiyo-cloud/TeraBridge-api — second evaluation candidate

- `LICENSE` contains an Apache License 2.0 grant and copyright notice. GitHub's automatic SPDX classification returned NOASSERTION; the actual file was inspected rather than treating that label as absence of a license.
- Last repository push reported: **2026-10-05**.
- Current implementation uses FastAPI/httpx/asyncio, despite the repository description mentioning Flask.
- `downloader.py` includes first-party share/session resolution, transfer, per-file processing and streaming logic. `api/routes/{resolve,download,stream}.py` include API resolution, download and stream handlers.
- `downloader.py:274` implements `/share/transfer`; per-file processing calls it, with `/cloudvids` as the configured root path. This is a state-changing account-backed workflow, not merely anonymous reading of a share page.
- Needs operator-managed credentials and potentially account storage. Account pool, Redis and rate/tier machinery add operational complexity. They are not needed merely to reproduce the desired English native UI, and should not introduce app-added user quotas.
- No account was configured, no transfer executed and no cleanup/delete operation performed in this review.

## Other candidates: what their source actually does

| Repository | Source finding | Disposition |
|---|---|---|
| Ronnie7866/terabox-downloader-online | Spring Boot extractor gets a session from `GithubCookieManager`, lists first-party files and follows returned dlinks. README “educational purposes” is not an explicit reusable open-source grant; no LICENSE found in inspected tree. | Reference only pending licensing clarification; do not import shared credentials. |
| abhinai2244/TeraBox-Dl | `src/lib/terabox.ts` loads configured ndus, scrapes jsToken and calls first-party `/share/list`. No clear license grant found in inspected tree/README. | Not a verified new no-login engine; licensing blocker. |
| eyeblech/tera-dl | MIT. `tera-dl.py` submits link plus configured session to `terasnap.netlify.app/api/download`. | Downloader/API client, not an independent extraction engine. |
| Itz-Ashlynn/Terabox-Web | Full MIT grant is in README. `netlify/functions/download.js` requires a cookies request field and lists first-party files. Generated proxy URL includes a cookie query parameter. | Not suitable unchanged; explicit credential UX and URL leakage concern. |
| Broblox124/Terabox-Downloader-API | MIT. `src/worker.js` uses a configured session; `src/terabox-auto-cookie.js` instead fetches a public credential pool. | Small worker reference; public pool must not be used. |
| Damantha126/TeraboxDL | MIT. Constructor rejects empty cookie; file-info method takes first file's dlink from `/share/list`; downloader has retry/resume code. Import also fetches a remote JSON version configuration. | Genuine Python first-party client, but not a verified link-only standalone solution or strongest multi-file resolver. |
| aruxyz/trauso | Rust/Tauri desktop client; `src-tauri/src/terabox/api.rs` calls `terabox.hnn.workers.dev`. Fallback endpoints share the same hostname. README says MIT but linked root LICENSE is absent in inspected tree; bundled aria2 has separate license obligations. | Useful queue/client architecture reference, not an independent resolver. Clarify licensing before reuse. |
| saahiyo/terabox-gateway | MIT. Current `fetch_download_link` delegates to a configured Cloudflare proxy (`mode=resolve`); config pins a third-party Worker hostname. | Do not confuse this wrapper with the newer TeraBridge implementation. |
| ivansaul/terabox_downloader | MIT. `helpers/helpers.py` calls an external Worker for both get-info and get-download. Last push 2023-11-06. | Older API client, not independent resolver. |
| abdul97233/TeraBox-Downloader-Bot | `terabox.py` invokes configured external API; example config requires API endpoints/tokens. | Telegram delivery/client layer, not self-contained extraction. |
| IcelandVlog/Terabox-Downloader | Current default-branch tree/source resembles the Bun implementation, uses configured session, no root LICENSE found. Search-index description differed substantially. | Use current source, not stale indexed README, when evaluating. |
| maiquocthinh/Terabox-DL | Metadata/tree only reviewed; archived, last push 2024-02-11. | Lower priority; no claim of implementation validation. |

## Required acceptance gate before an APK

- Pin and preserve upstream license notices; review all runtime dependencies.
- Do not execute unknown install scripts or fetch/use public session pools.
- Do not forward the user's share to an unrelated hosted resolver without explicit consent.
- Test with authorized public content: metadata alone is insufficient. Require actual nonempty media bytes and an appropriate content signature/type.
- Verify Range/206, Content-Range and resume integrity; no appending a full 200 response to a partial file.
- Test seeking/playback in Media3 on Android 9, expiry handling, network interruption, multi-file shares and quality availability.
- Report unresolved verification, 403/429 and transport failures honestly. No silent changes to the existing NewPipe no-bypass behavior.
- A TeraBox candidate's refresh policy must remain isolated from NewPipe; preserve Android 9 URL-codec and FFmpeg runtime fixes.

## What did not happen

No candidate was installed or executed. No candidate downloaded media successfully in this review. No third-party resolver received the user's sample link. No shared credentials were used. No backend was deployed. No APK/project code or launcher resources were changed. The selected SD icon remains separate.

The previous first-party live probe is separately documented in `TERABOX-LIVE-PROBE-2026-10-11.md`; its metadata-only success and `need verify_v2` response do not establish universal impossibility or prove any candidate works.

## Pinned source evidence

The following links identify inspected default-branch snapshots. Relevant implementation filenames are listed above. Local disposable source copies are not shipped as application dependencies.

- [Broblox124/Terabox-Downloader-API — `5b6fd4c85841`](https://github.com/Broblox124/Terabox-Downloader-API/tree/5b6fd4c8584151a823f34ccc9132a4ecd86aede3)
- [Damantha126/TeraboxDL — `dbf7f2ce4be5`](https://github.com/Damantha126/TeraboxDL/tree/dbf7f2ce4be527c529134b368f9dd2882e0c11a3)
- [IcelandVlog/Terabox-Downloader — `dff372cc40a0`](https://github.com/IcelandVlog/Terabox-Downloader/tree/dff372cc40a001d43e74183497a608938e804883)
- [Itz-Ashlynn/Terabox-Web — `49c85441275b`](https://github.com/Itz-Ashlynn/Terabox-Web/tree/49c85441275b039312d4b6eed103aa4e1a709491)
- [Paidguy/terabox-api — `4d3149a22888`](https://github.com/Paidguy/terabox-api/tree/4d3149a22888018375a933d1c0c0ebc3eef32fb6)
- [Ronnie7866/terabox-downloader-online — `50a2705bbb1b`](https://github.com/Ronnie7866/terabox-downloader-online/tree/50a2705bbb1bbfde22083a063e1e0f65b471215d)
- [abdul97233/TeraBox-Downloader-Bot — `5a22188d8510`](https://github.com/abdul97233/TeraBox-Downloader-Bot/tree/5a22188d85107ce23018cb839f33a13dd3016f37)
- [abhinai2244/TeraBox-Dl — `314059bc9d09`](https://github.com/abhinai2244/TeraBox-Dl/tree/314059bc9d092b1a966c2417b75d313f3a487793)
- [aruxyz/trauso — `08bbb8f06714`](https://github.com/aruxyz/trauso/tree/08bbb8f067140189f6a245a7fbbcdb070017f5c2)
- [eyeblech/tera-dl — `fd37393fcd65`](https://github.com/eyeblech/tera-dl/tree/fd37393fcd6552c3137867a6f09b90add1ee6768)
- [ivansaul/terabox_downloader — `b4ca5c8094e0`](https://github.com/ivansaul/terabox_downloader/tree/b4ca5c8094e0fe15a2f4e89a1d29547e2beec41b)
- [maiquocthinh/Terabox-DL — `3f989e953790`](https://github.com/maiquocthinh/Terabox-DL/tree/3f989e9537904167497699ded45347f33102d798)
- [muxfox/Terabox-Downloader-API — `5b6fd4c85841`](https://github.com/muxfox/Terabox-Downloader-API/tree/5b6fd4c8584151a823f34ccc9132a4ecd86aede3)
- [saahiyo/terabox-gateway — `063f61fc61bb`](https://github.com/saahiyo/terabox-gateway/tree/063f61fc61bb173fb936fc487f4a18e40436caab)
- [saahiyo-cloud/TeraBridge-api — `93acdc46846e`](https://github.com/saahiyo-cloud/TeraBridge-api/tree/93acdc46846e03a21a7c452c335ba729906c1128)

Primary shortlisted files:

- [Paidguy/terabox-api/src/lib/terabox.ts](https://github.com/Paidguy/terabox-api/blob/4d3149a22888018375a933d1c0c0ebc3eef32fb6/src/lib/terabox.ts)
- [Paidguy/terabox-api/src/handlers.ts](https://github.com/Paidguy/terabox-api/blob/4d3149a22888018375a933d1c0c0ebc3eef32fb6/src/handlers.ts)
- [Paidguy/terabox-api/src/lib/cookies.ts](https://github.com/Paidguy/terabox-api/blob/4d3149a22888018375a933d1c0c0ebc3eef32fb6/src/lib/cookies.ts)
- [Paidguy/terabox-api/LICENSE](https://github.com/Paidguy/terabox-api/blob/4d3149a22888018375a933d1c0c0ebc3eef32fb6/LICENSE)
- [saahiyo-cloud/TeraBridge-api/downloader.py](https://github.com/saahiyo-cloud/TeraBridge-api/blob/93acdc46846e03a21a7c452c335ba729906c1128/downloader.py)
- [saahiyo-cloud/TeraBridge-api/api/routes/resolve.py](https://github.com/saahiyo-cloud/TeraBridge-api/blob/93acdc46846e03a21a7c452c335ba729906c1128/api/routes/resolve.py)
- [saahiyo-cloud/TeraBridge-api/LICENSE](https://github.com/saahiyo-cloud/TeraBridge-api/blob/93acdc46846e03a21a7c452c335ba729906c1128/LICENSE)

## Follow-up: Android projects and Telegram-bot architecture

Reviewed after user authorized selecting a suitable engine and requested Android/bot alternatives:

- `veloredge/vetera`, revision `52268d3f79545b0a27ad7baa6e6bc74e975a47d4`: cloned default branch contains README, LICENSE and .gitignore, no Android source/build project. README claims Kotlin/Compose and anonymous preview streaming; these are not implementation-verified. Not an importable Android engine at this revision.
- `r0ld3x/terabox-player-app`, revision `7cf38928f89574d6cd86e497a53d0362c243127b`: actual Expo/React Native source and GPL-3.0 license text. `App.tsx:40–41` invokes `https://ytshorts.savetube.me/api/v1/terabox-downloader`; this is a mobile API client, not a self-contained TeraBox resolver. No request was sent to this service.
- `codex67cm/TeraboxDownloader`, revision `0fce9117375adaed1a7055a29bdd9212238e01c4`: Electron/JavaScript desktop files, not a native Android project.
- `r0ld3x/teraboxdownloader-android`: public clone failed with an authentication prompt; source not evaluated. Do not interpret an old search index as proof of current source availability.

A bot's messaging layer is separate from its resolver/transfer layer. The latter can be used behind a native app when its source/license or service permission permits. Telegram Bot API is not an arbitrary existing-bot downloader API; a bot username alone gives neither its token nor its private backend. Prefer a directly controlled resolver API rather than automating another bot through a user's Telegram session. No bot integration, backend deployment or APK change occurred in this follow-up.
