# TeraBox resolver for StreamDock — setup that needs no end-user login

StreamDock's Tera tab already resolves shares directly with TeraBox. When TeraBox does not issue a
download link to an anonymous session (observed on the sample share, see `TERABOX-LIVE-PROBE-2026-10-11.md`
and `TERABOX-ENGINE-1.3.0.md`), the app can use an **operator-run resolver** instead. The end user still
never enters a password or pastes a cookie: only the operator configures a session on the server.

This is the option that keeps the "paste a link, then nothing else is needed" behaviour for users.

## 1. Run the reviewed MIT resolver (Cloudflare Workers, free tier is enough)

```bash
npm install -g wrangler            # Node 20+
git clone https://github.com/Paidguy/terabox-api
cd terabox-api
npm install
```

In `wrangler.toml` set the secrets with the commands below (never edit them into the file):

```bash
wrangler secret put TERABOX_COOKIE   # the operator's own TeraBox session cookie: ndus=...; other=...
wrangler secret put API_KEYS         # optional, if the Worker supports keyed access for your app
wrangler deploy                      # publishes e.g. https://tera-resolver.<account>.workers.dev
```

Notes
- `TERABOX_COOKIE` belongs to the operator's own TeraBox account. Do not use other people's cookies and do not
  publish this value. Rotate it when the account signs out elsewhere.
- The free plan has request limits; a shared service can hit them. Do not promise unlimited downloads.
- `RATE_LIMIT=0` disables the Worker's own per-IP limit, but the platform limit still applies.

## 2. Verify the deployment before touching the app

```bash
./tools/tera-resolver-smoke.sh https://tera-resolver.<account>.workers.dev "https://1024terabox.com/s/YOUR-SHARE"
```

The script checks the contract the app expects:

```
GET /api/resolve?link=<url-encoded share link>
→ {"status":"ok","files":[{"fs_id":"…","file_name":"…","size_bytes":123,"isdir":0,"stream_url":"https://…"}]}
```

It then performs a 1 KB `Range` read from the returned `stream_url` and verifies a real media signature
(MP4/WebM/TS/MP3/Ogg/RIFF). Metadata alone is never treated as success.

## 3. Point the app at it

StreamDock → **Tera** tab → **Advanced → Resolver address** → enter the HTTPS origin
(for example `https://tera-resolver.<account>.workers.dev`) → *Use this resolver*.

After that, a pasted share link is resolved by your service, and **Play** / **Save** use the returned
`stream_url` through the same validated transfer path (bounded retries, `Content-Range`/`If-Range`
resume checks, no cookie forwarding). The origin is added to the app's host allowlist for that session only.

## 4. What stays true

- No account password, cookie import or browser-session export in the app UI.
- No third-party downloader website is used or embedded.
- The app never weakens TLS, never solves a verification challenge, and reports a missing download link honestly.
- If the operator's session expires, the app shows the resolver's error instead of inventing a result.

## 5. Alternatives considered

- `saahiyo-cloud/TeraBridge-api` (Apache-2.0): Python/FastAPI, but its download path copies shared files into the
  operator account (`/share/transfer`, `/cloudvids`), which consumes account storage. Use it only if you accept that.
- Public cookie pools and "auto-cookie" helpers found in other projects are deliberately **not** used.

## 2026-10-13 update — the bundled service and the `need verify_v2` wall

The anonymous API now answers **`errno 400310 / "need verify_v2"`** to a fully browser-identical request
(real cookies + `sign` + `timestamp` + `jsToken`, the exact `GET /share/download` form the web player uses)
on **all five** TeraBox origins; `/share/streaming` answers `errno 31023`; `/share/tplconfig` is retired
(HTML on every host). Evidence table: `docs/TERABOX-VERIFY-1.4.0.md`.

The source archive therefore **ships the MIT resolver** at `backend/terabox-api/` (Paidguy/terabox-api,
rev `4d3149a…`), with `backend/README-STREAMDOCK.md` giving a free deploy recipe (wrangler **or** the
included GitHub Actions workflow) plus how to set `TERABOX_COOKIE` as a secret on your own Cloudflare
account. The app's **Advanced → Resolver settings** accepts the bare `name.workers.dev` host, and resolver
errors are now reported by code (`verification_required`, `cookie_invalid`, `password_required`).

Without a resolver, the honest paths are: complete TeraBox's own verification **inside the app browser**
and let the page play (then save the captured HLS stream), or stay with the verified file list only.
