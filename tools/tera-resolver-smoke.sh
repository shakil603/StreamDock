#!/usr/bin/env bash
# StreamDock — operator resolver smoke test (never prints or stores any credential).
# Usage:  ./tera-resolver-smoke.sh https://resolver.example.com "https://1024terabox.com/s/SHARE"
# It checks only the documented contract used by the app: GET /api/resolve?link=… then a 1 KB range read.
set -u
ORIGIN="${1:-}"; SHARE="${2:-}"
if [ -z "$ORIGIN" ] || [ -z "$SHARE" ]; then echo "usage: $0 <https-origin> <terabox-share-link>"; exit 2; fi
case "$ORIGIN" in https://*) ;; *) echo "FAIL: origin must be https"; exit 2;; esac
UA="Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
echo "1) metadata contract"
RESP="$(curl -sS --max-time 40 -H "User-Agent: $UA" "$ORIGIN/api/resolve?link=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=""))' "$SHARE")")" || { echo "FAIL: request error"; exit 1; }
python3 - "$RESP" <<'PY'
import json,sys
raw=sys.argv[1]
try: data=json.loads(raw)
except Exception: print("FAIL: response is not JSON (first 200 chars):",raw[:200]); sys.exit(1)
status=data.get("status"); files=data.get("files") or []
print("   status:",status,"files:",len(files))
if status!="ok" or not files:
    print("   server said:",str(data.get("error") or data.get("message") or "")[:300]); print("FAIL: no resolvable file"); sys.exit(1)
for f in files[:5]:
    print("   -",f.get("file_name"),f.get("size_bytes"),"stream_url" if f.get("stream_url") else "NO-stream_url")
first=next((f for f in files if not f.get("isdir")),None)
url=(first or {}).get("stream_url") or ""
open("/tmp/tera-smoke-url","w").write(url)
print("MEDIA_URL_PRESENT" if url.startswith("https://") else "NO_MEDIA_URL")
PY
[ "$(tail -1 <<<"$(cat /tmp/tera-smoke-url 2>/dev/null)")" = "" ] && { echo "FAIL: no stream URL"; exit 1; }
URL="$(cat /tmp/tera-smoke-url)"
echo "2) media byte check (1 KB range)"
curl -sS --max-time 40 -H "User-Agent: $UA" -H "Range: bytes=0-1023" -D /tmp/tera-smoke-head -o /tmp/tera-smoke-body "$URL" || { echo "FAIL: media request error"; exit 1; }
grep -iE '^(HTTP/|content-type|content-range|content-length)' /tmp/tera-smoke-head
python3 - <<'PY'
head=open("/tmp/tera-smoke-body","rb").read(16)
ascii_head="".join(chr(b) if 32<=b<127 else "." for b in head)
known = (head[4:8] in (b"ftyp",b"moov",b"mdat")) or head[:4]==b"\x1aE\xdf\xa3" or head[:1]==b"G" or head[:3]==b"ID3" or head[:4]==b"RIFF" or head[:4]==b"OggS"
print("   first bytes:",ascii_head, "recognised media signature:", known)
print("PASS: resolver contract + real media bytes" if known else "FAIL: response is not recognised media (a page/JSON error may have been returned)")
raise SystemExit(0 if known else 1)
PY
rm -f /tmp/tera-smoke-url /tmp/tera-smoke-head /tmp/tera-smoke-body
