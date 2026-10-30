#!/usr/bin/env bash
# security-test.sh <tv-ip> <pin> - re-runs the phone-controller security tests against Glass (release build).
set -u
IP=$1; PIN=$2; U="http://$IP:8642"; H="Host: $IP:8642"
c() { curl -s -m 10 -o /tmp/glass-body -w '%{http_code}' "$@"; }
pass=0; fail=0
t() { local name=$1 want=$2 got=$3; if [ "$got" = "$want" ]; then pass=$((pass+1)); echo "PASS  $name -> $got"; else fail=$((fail+1)); echo "FAIL  $name -> $got (wanted $want)  $(head -c 120 /tmp/glass-body)"; fi; }
echo "### release security tests, $(date)"
t "1  wrong PIN"                      401 "$(c -X POST -H "$H" -d '{"pin":"000000","name":"test"}' "$U/api/pair")"
GOOD='{"pin":"'$PIN'","name":"Mac (release test)"}'   # in a variable: old bash splits {a,b} inside $(...)
t "2  right PIN"                      200 "$(c -X POST -H "$H" -d "$GOOD" "$U/api/pair")"
TOK=$(python3 -I -c 'import json;print(json.load(open("/tmp/glass-body"))["token"])'); NAME=$(python3 -I -c 'import json;print(json.load(open("/tmp/glass-body"))["name"])')
t "3  token is 256 bits"              64 "${#TOK}"
t "3b TV name is neutral"             "Glass TV" "$NAME"
t "4  no token"                       401 "$(c -H "$H" "$U/api/status")"
t "5  wrong token"                    401 "$(c -H "$H" -H "X-Glass-Token: $(printf 'f%.0s' {1..64})" "$U/api/status")"
t "6  wrong Host (DNS rebinding)"     421 "$(c -H "Host: evil.example.com:8642" -H "X-Glass-Token: $TOK" "$U/api/status")"
t "7  foreign Origin"                 403 "$(c -X POST -H "$H" -H "Origin: http://evil.example.com" -H "X-Glass-Token: $TOK" -d '{"key":"volup"}' "$U/api/key")"
t "8  Sec-Fetch-Site cross-site"      403 "$(c -H "$H" -H "Sec-Fetch-Site: cross-site" -H "X-Glass-Token: $TOK" "$U/api/status")"
t "9  CORS preflight"                 405 "$(c -X OPTIONS -H "$H" -H "Origin: http://$IP:8642" "$U/api/status")"
t "9b no CORS headers"                0 "$(curl -s -D - -o /dev/null -H "$H" -H "X-Glass-Token: $TOK" "$U/api/status" | grep -ci '^access-control')"
t "10 good request"                   200 "$(c -H "$H" -H "X-Glass-Token: $TOK" "$U/api/status")"
cp /tmp/glass-body /tmp/glass-status.json
t "10b no weather city by default"    None "$(python3 -I -c 'import json;print(json.load(open("/tmp/glass-status.json"))["weather"].get("place"))')"
t "10c inputs are neutral"            "HDMI 1,HDMI 2,HDMI 3,AV" "$(python3 -I -c 'import json;print(",".join(i["label"] for i in json.load(open("/tmp/glass-status.json"))["inputs"]))')"
t "11 rename input"                   200 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{"key":"hdmi1","label":"PlayStation"}' "$U/api/input/rename")"
c -H "$H" -H "X-Glass-Token: $TOK" "$U/api/status" >/dev/null
t "11b renamed label shows"           PlayStation "$(python3 -I -c 'import json;print(json.load(open("/tmp/glass-body"))["inputs"][0]["label"])')"
t "11c rename back to neutral"        200 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{"key":"hdmi1","label":""}' "$U/api/input/rename")"
t "11d unknown input refused"         400 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{"key":"hdmi9","label":"x"}' "$U/api/input/rename")"
t "12 ftp link refused"               400 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{"url":"ftp://example.com/x"}' "$U/api/open")"
t "13 http APK refused"               400 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{"url":"http://example.com/a.apk"}' "$U/api/install")"
for i in 1 2 3 4; do c -X POST -H "$H" -d '{"pin":"111111","name":"test"}' "$U/api/pair" >/dev/null; done
t "14 5th wrong PIN locks pairing"    423 "$(c -X POST -H "$H" -d '{"pin":"111111","name":"test"}' "$U/api/pair")"
t "15 right PIN while locked"         423 "$(c -X POST -H "$H" -d "$GOOD" "$U/api/pair")"
t "16 unpair this test client"        200 "$(c -X POST -H "$H" -H "X-Glass-Token: $TOK" -d '{}' "$U/api/unpair")"
t "17 token dead after unpair"        401 "$(c -H "$H" -H "X-Glass-Token: $TOK" "$U/api/status")"
t "18 loopback not served"            000 "$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8642/ 2>/dev/null)"
echo "### $pass passed, $fail failed"
