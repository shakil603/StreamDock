# Public TeraBox share probe — 2026-10-11

User-supplied sample: https://1024terabox.com/s/1RBVO1eibUTx3GM9-8APX-g

## Scope

Read-only requests from the workspace network, without an imported account session, account password, paid credentials or third-party resolver. Only normal anonymous cookies issued during the public page visit were used. Public frontend JavaScript was read as text, not executed. No verification challenge was solved or bypassed. No video bytes were downloaded.

## Observations

- Public share page: HTTP 200, redirected to `www.1024tera.com`.
- The server-rendered page exposes public file metadata and share-resolution parameters. Do not persist its temporary tokens as app constants.
- `/share/list`: HTTP 200, application `errno=0`, one MP4 item, size **164920251 bytes**. The returned item has **no usable `dlink`**.
- Repeating the list request on the page's redirected origin with its anonymous session and exposed `jsToken` still returned metadata but no dlink.
- A read-only `/share/download` resolution request using the public share signature, timestamp, share/file IDs returned HTTP 200 with **`errno=400310`, `errmsg="need verify_v2"`**, and no download URL.
- This was an endpoint probe, not a complete reproduction of the current interactive web client's verification flow. It does not establish that the request will behave identically on the user's phone/network.

## Conclusions and non-conclusions

Metadata resolution succeeded. Direct downloadable media resolution and playback/download did **not** succeed in this probe. A list-only adapter that expects the first item to contain a dlink is insufficient for this sample in this environment.

The observed response asks for verification; it does **not** by itself prove that an account password, user-supplied cookies, a backend server, or premium access is required. It also does not prove why Savesta or Parrot might succeed: their internal implementation/network flow was not inspected.

An appropriate next implementation needs to distinguish successful metadata lookup from a ready download, handle verification explicitly through the first-party service where feasible, and verify the resulting original-media response before claiming success. It must not silently send the share to an unrelated third-party resolver, weaken TLS verification, or present a verification failure as a completed download.

No APK, native TeraBox integration, retry/resume fix or launcher-icon integration was built during this probe. The currently packaged deliverables remain version 1.2.0. The selected new SD icon exists separately as `StreamDock-SD-icon.png`.
