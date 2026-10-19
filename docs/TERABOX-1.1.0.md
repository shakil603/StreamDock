> HISTORICAL: the native TeraBox adapter was removed in 1.2.0 by user request. See the current README and BROWSER-1.2.0.md.

# TeraBox integration — 1.1.0 (experimental)

**Status: implemented and fixture-tested, not live TeraBox-validated.** No real share URL, account credentials or authorized account sample was provided. No real TeraBox download, phone UI, Android Keystore execution or Android audio conversion was run for this feature in this workspace. The user independently confirmed the previous 1.0.4 app worked on their Symphony Z25; that confirmation does not validate this new integration.

## ফোনে ব্যবহার

1. ARM64 APK পুরোনো app-এর ওপর update হিসেবে install করুন; footer **v1.1.0**। একই signing certificate, versionCode 6। পুরোনো app uninstall করার প্রয়োজন নেই।
2. **TeraBox · Public shares / My files** খুলুন। পরিচিত TeraBox HTTPS share link মূল analysis box-এ দিলেও এই screen খুলবে।
3. **Public:** My files checkbox বন্ধ রাখুন → share link দিন → প্রয়োজন হলে নিজের পাওয়া share code দিন → **ফাইল দেখুন / Refresh**।
4. Folder খুলুন; আগের/পরের page দিয়ে তালিকা দেখুন। **Root folder** মূল তালিকায় ফেরায়।
5. আগে **Original / MP3 / M4A** বেছে নিন, তারপর ফাইলে চাপুন ও save location দিন। Original-এ মূল extension/container রাখা হয়—MKV-কে MP4 নামে চালানো হয় না। MP3/M4A-তে পুরো source download করে local FFmpeg audio conversion হয়।
6. **My files:** নিজের logged-in browser-এর Netscape `cookies.txt` import করুন, তারপর checkbox চালু করে **ফাইল দেখুন**। Website button শুধু official browser page খোলে; সেটি automatic cookie import বা native account-login screen নয়। Android Chrome সাধারণত cookies.txt export করে না; উপযুক্ত browser বা নিজের PC লাগতে পারে।
7. Cookies file/password কখনো চ্যাটে, online converter বা তৃতীয়-পক্ষ downloader server-এ দেবেন না। Import করার পর external Downloads-এ থাকা plaintext export নিজে মুছুন—অ্যাপ ইচ্ছামতো আপনার নির্বাচিত source document মুছবে না।
8. Session মুছতে **TeraBox session ও task data মুছুন**। এটি স্থানীয় TeraBox jobs বাতিল/মুছে দেয়; server session revoke করে না। প্রয়োজন হলে official TeraBox site-এ logout/session management ব্যবহার করুন।

TeraBox error text/transfer status long-press করে copy করা যায়। Report পাঠালে credentials বাদ দিন। TeraBox screen-এ screenshots/recents preview blocked (`FLAG_SECURE`)।

## বাস্তব সীমা — “unlimited” দাবি নয়

- No added daily download count, media byte quota or bandwidth throttle. Existing single-active-transfer policy remains to manage device resources.
- Pagination uses 100-item requests; this is not a total-file limit. Server `has_more` is honored when present.
- Metadata responses are defensively bounded to 4 MiB and cookie imports to 1 MiB. These limits do not cap media size.
- Server entitlement, file/share ownership, login expiry, CAPTCHA, regional availability, premium policy, throttling and link expiry remain authoritative. No bypass/solver/proxy/rotating credentials.
- Public shares may require a valid own-account session. This adapter needs the server to return a usable original `dlink`; it does not fabricate links when the API omits one.
- Download is progressive HTTPS only; no DRM removal, adaptive TeraBox streaming extraction or video-quality conversion. Original can preserve a video or audio file. Audio extraction fails clearly if the file has no readable audio track.
- No persistent range resume, automatic batch queue, auto-retry on 403/429 or yt-dlp fallback for TeraBox. A stopped/expired transfer must be selected again.
- Source staging, audio output and SAF export require available storage; Android/background/SAF-provider limits also apply. A failed/cancelled export can leave an empty/partial destination document: remove that file yourself.
- The APIs are not a supported public SDK contract. The adapted official path omits upstream's `vip=2` hint; actual behavior with free/premium accounts remains unverified. API changes may require adapter updates.

## Architecture and provenance

`MainActivity` routes recognized TeraBox hosts to `terabox/TeraBoxActivity`, not NewPipe/yt-dlp.

```
Public share + optional local share code          Own account (imported own cookies)
     /share/verify -> /share/list                  /api/check/login -> /api/list
                  \                                /
                   paginated folder/file selection
                                 |
                encrypted job + random WorkManager reference
                                 |
           fresh authorized link (share re-list / official account flow)
                                 |
                  cookie-scoped HTTPS range downloader
                                 |
                    Original OR local FFmpeg audio
                                 |
                             SAF export
```

Account download: `/api/home/info` -> authenticated signature -> `/api/download?type=dlink&fidlist=…`. The signature function is a standard RC4-derived operation on the account's own returned data; it does not derive someone else's credentials or override permissions.

OpenList reference revision: `482464c2affc5eeba5d4a25bb272aeb352ec5918`. It is a read-only Kotlin adaptation, not the entire OpenList engine or a Go service. No write/upload/delete APIs, `linkCrack`, `vip=2` override or third-party proxy are present. Public-share handling is a separate new adapter informed by the share-list request shape in `RrY3999/TeraBox`; that script is not bundled. See `terabox-upstream/provenance.json` and `THIRD_PARTY_NOTICES.md`.

The original application and vendored NewPipe retain their GPL terms. The TeraBox adaptation uses AGPL-3.0-only; distribution of the combined work must comply with GPLv3/AGPLv3 section 13. Both license texts and the editable adapter are provided. Existing native-wrapper corresponding-source caveats still apply.

## Session security

- AES-256-GCM with Android Keystore non-exportable key, fresh nonce and filename-bound additional authenticated data. Stored only beneath app `noBackupFilesDir`; manifest `allowBackup=false` remains.
- Netscape import keeps only non-expired cookies whose domains are `terabox.com` or its subdomains. Host-only/domain and path scope are preserved; requests are HTTPS-only. Unrelated browser cookies are discarded, never retained in the encrypted session file.
- Import errors and malformed API responses do not echo cookies, verification tokens or raw response bodies. Diagnostic redaction covers URLs and credential-bearing lines.
- CookieJar chooses cookies for every request/redirect. Unrelated CDN hosts receive no TeraBox cookies. API-region hints are restricted to a bounded first-party subdomain label, and token/region refresh is finite.
- Share password and task details are encrypted. WorkManager Data contains a random job UUID and destination URI, not cookies/passwords/signed media URLs. Password UI is not saved to instance-state bundles. Task files are removed on normal worker completion; interrupted picker/orphan encrypted jobs can be removed using the clear-data control.
- The import file exists outside the app until the user deletes it. A debug build, trusted ADB, rooted/compromised phone or stolen active browser session is not a strong security boundary. This is not a production credential-vault audit.

## Acceptance checklist (still requires your authorized live tests)

- [ ] Public single-video link: Original; saved length and playback.
- [ ] Password-protected share: correct code succeeds, wrong code fails without retries.
- [ ] Share folder/subfolder and more than one page; selected file, not the first item, downloads.
- [ ] Own valid cookies: account root/subfolder list and a private file download.
- [ ] Expired/absent cookies: clear failure and successful re-import.
- [ ] MP3 and M4A from an authorized video with an audio track on Symphony Z25 / Android 9.
- [ ] Cancel during network and export; clear session; verify no later authenticated request.
- [ ] Recheck the previously working NewPipe quality/download/merge and local conversion paths.

Do not check these boxes based on fixture tests alone.
