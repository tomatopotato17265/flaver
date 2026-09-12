#!/usr/bin/env bash

set -uo pipefail
BASE="${BASE_URL:-http://localhost:8787}"
PASS=0; FAIL=0

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n     %s\n' "$1" "${2:-}"; FAIL=$((FAIL+1)); }
head() { printf '\n\033[1m%s\033[0m\n' "$1"; }

req() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  local args=(-s -o /tmp/flaver_smoke_body -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ]  && args+=(-H 'Content-Type: application/json' -d "$data")
  STATUS=$(curl "${args[@]}")
  BODY=$(cat /tmp/flaver_smoke_body)
}

expect_status() {
  if [ "$STATUS" = "$2" ]; then ok "$1"; else bad "$1" "expected HTTP $2, got $STATUS: $BODY"; fi
}
expect_json() {
  local got; got=$(printf '%s' "$BODY" | jq -r "$2" 2>/dev/null)
  if [ "$got" = "$3" ]; then ok "$1"; else bad "$1" "$2 => '$got', expected '$3'  (body: $BODY)"; fi
}

login() {
  curl -s -X POST "$BASE/api/auth/dev" -H 'Content-Type: application/json' \
    -d "{\"uuid\":\"$1\",\"username\":\"$2\"}" | jq -r '.token'
}

A_UUID=11111111-1111-4111-8111-111111111111
B_UUID=22222222-2222-4222-8222-222222222222
C_UUID=33333333-3333-4333-8333-333333333333
D_UUID=44444444-4444-4444-8444-444444444444

head "Health"
req GET /
expect_status "service responds" 200
expect_json  "reports service name" '.service' 'flaver-backend'

head "Auth"
A_TOK=$(login $A_UUID Alice); B_TOK=$(login $B_UUID Bob)
C_TOK=$(login $C_UUID Carol); D_TOK=$(login $D_UUID Dave)
[ -n "$A_TOK" ] && [ "$A_TOK" != null ] && ok "dev login issues a token" || bad "dev login issues a token" "$A_TOK"

req GET /api/me
expect_status "no token is rejected" 401
req GET /api/me "not-a-real-token"
expect_status "garbage token is rejected" 401
req GET /api/me "v1.eyJ1IjoiMTExMTExMTEtMTExMS00MTExLTgxMTEtMTExMTExMTExMTExIiwiZSI6OTk5OTk5OTk5OX0.forgedsignature"
expect_status "forged signature is rejected" 401
req GET /api/me "$A_TOK"
expect_status "valid token is accepted" 200
expect_json  "identifies the caller" '.username' 'Alice'

req POST /api/auth/verify "" '{"username":"Alice","serverId":"00000000000000000000000000000000"}'
expect_status "unknown challenge is refused" 400
expect_json  "  with challenge_invalid" '.error' 'challenge_invalid'

req POST /api/auth/challenge
expect_status "challenge is issued" 200
CHAL=$(printf '%s' "$BODY" | jq -r '.serverId')
req POST /api/auth/verify "" "{\"username\":\"Alice\",\"serverId\":\"$CHAL\"}"

if [ "$STATUS" = 401 ] || [ "$STATUS" = 502 ]; then ok "real verify reaches Mojang and declines unproven session"
else bad "real verify reaches Mojang and declines unproven session" "got $STATUS: $BODY"; fi
req POST /api/auth/verify "" "{\"username\":\"Alice\",\"serverId\":\"$CHAL\"}"
expect_json "challenge is single-use" '.error' 'challenge_invalid'

head "Servers and presence"
req POST /api/servers "$A_TOK" '{"name":"Alice'"'"'s World"}'
expect_status "server registers" 201
SID=$(printf '%s' "$BODY" | jq -r '.serverId')

req POST /api/presence "$A_TOK" "{\"serverId\":\"$SID\",\"tunnelHostname\":\"demo-host.trycloudflare.com\",\"mcVersion\":\"26.2\"}"
expect_status "heartbeat accepted" 200
req GET /api/servers "$A_TOK"
expect_json "owner sees own server online" '.servers[0].online' 'true'

req POST /api/presence "$B_TOK" "{\"serverId\":\"$SID\",\"tunnelHostname\":\"evil.trycloudflare.com\",\"mcVersion\":\"26.2\"}"
expect_status "cannot heartbeat someone else's server" 404
req POST /api/presence "$A_TOK" "{\"serverId\":\"$SID\",\"tunnelHostname\":\"not a hostname!\"}"
expect_status "malformed hostname rejected" 400

head "Friend-only discovery (the security boundary)"
req GET "/api/friends/$A_UUID/servers" "$B_TOK"
expect_status "stranger cannot list Alice's servers" 403
expect_json  "  and is told only that they are not friends" '.error' 'not_friends'

req POST /api/friends/request "$A_TOK" '{"username":"Nonexistent"}'
expect_status "request to unregistered player 404s" 404
expect_json  "  with player_not_registered" '.error' 'player_not_registered'
req POST /api/friends/request "$A_TOK" '{"username":"Alice"}'
expect_status "cannot friend yourself" 400

req POST /api/friends/request "$A_TOK" '{"username":"Bob"}'
expect_json "Alice requests Bob" '.status' 'requested'
req GET /api/friends "$B_TOK"
expect_json "Bob sees the incoming request" '.incoming[0].username' 'Alice'
req GET /api/friends "$A_TOK"
expect_json "Alice sees it as outgoing" '.outgoing[0].username' 'Bob'

req GET "/api/friends/$A_UUID/servers" "$B_TOK"
expect_status "a pending request grants no access" 403

req POST /api/friends/accept "$B_TOK" "{\"uuid\":\"$A_UUID\"}"
expect_status "Bob accepts" 200
req GET /api/friends "$B_TOK"
expect_json "Bob now has Alice as a friend" '.friends[0].username' 'Alice'
expect_json "  shown as online" '.friends[0].online' 'true'
expect_json "  with the world listed" '.friends[0].servers[0].name' "Alice's World"
expect_json "  but NO hostname in the list payload" '.friends[0].servers[0].tunnelHostname' 'null'
expect_json "  and the request is cleared" '.incoming | length' '0'

req GET "/api/friends/$A_UUID/servers" "$B_TOK"
expect_status "friend can now discover" 200
expect_json "  and receives the hostname" '.servers[0].tunnelHostname' 'demo-host.trycloudflare.com'

req GET "/api/friends/$A_UUID/servers" "$C_TOK"
expect_status "an unrelated third party still cannot" 403

head "Going offline"
req DELETE "/api/presence/$SID" "$A_TOK"
expect_status "clean shutdown accepted" 200
req GET "/api/friends/$A_UUID/servers" "$B_TOK"
expect_json "friend now sees it offline" '.servers[0].online' 'false'
expect_json "  and the hostname is withheld" '.servers[0].tunnelHostname' 'null'

head "Mutual requests and unfriending"
req POST /api/friends/request "$C_TOK" '{"username":"Dave"}'
expect_json "Carol requests Dave" '.status' 'requested'
req POST /api/friends/request "$D_TOK" '{"username":"Carol"}'
expect_json "Dave's crossing request auto-accepts" '.status' 'accepted'
req GET /api/friends "$C_TOK"
expect_json "Carol and Dave are friends" '.friends[0].username' 'Dave'
expect_json "  with no request left pending" '.outgoing | length' '0'

req POST /api/friends/request "$C_TOK" '{"username":"Dave"}'
expect_status "re-requesting an existing friend 409s" 409

req DELETE "/api/friends/$D_UUID" "$C_TOK"
expect_status "unfriend accepted" 200
req GET "/api/friends/$D_UUID/servers" "$C_TOK"
expect_status "access revoked after unfriending" 403

head "Result"
printf '  %d passed, %d failed\n\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
