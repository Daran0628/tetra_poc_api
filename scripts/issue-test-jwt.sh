#!/usr/bin/env bash
# 로컬 수동 테스트용 입장 토큰(JWT, RS256) 발급 — 테넌트 역할을 흉내 낸다.
# local-keys/test-jwt-private.pem (scripts/gen-test-keys.sh 로 생성) 으로 서명하고,
# 브라우저/curl 에 바로 넣을 세션 API URL 을 출력한다.
#
# 사용: PoC 폴더에서
#   bash scripts/issue-test-jwt.sh                  # user_id 무작위, event 1, 90초 만료
#   bash scripts/issue-test-jwt.sh user-0001        # user_id 지정
#   bash scripts/issue-test-jwt.sh user-0001 1 poctenant001 90 http://localhost:8080
#                                  [user_id] [event_id] [tenant_id] [ttl초] [base_url]
# 토큰은 1회용(jti)이라 같은 URL 을 두 번 열면 두 번째는 JWT_REUSED 가 정상.
set -euo pipefail

POC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PRIVATE_KEY="$POC_DIR/local-keys/test-jwt-private.pem"

USER_ID="${1:-user-$(openssl rand -hex 4)}"
EVENT_ID="${2:-1}"
TENANT_ID="${3:-poctenant001}"
TTL="${4:-90}"
BASE_URL="${5:-http://localhost:8080}"

if [[ ! -f "$PRIVATE_KEY" ]]; then
  echo "개인키가 없습니다: $PRIVATE_KEY  (먼저 bash scripts/gen-test-keys.sh)" >&2
  exit 1
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
JTI=$(openssl rand -hex 16)
HEADER='{"alg":"RS256","typ":"JWT"}'
PAYLOAD=$(printf '{"user_id":"%s","tenant_id":"%s","event_id":%s,"iat":%s,"exp":%s,"jti":"%s"}' \
  "$USER_ID" "$TENANT_ID" "$EVENT_ID" "$NOW" "$((NOW + TTL))" "$JTI")

SIGNING_INPUT="$(printf '%s' "$HEADER" | b64url).$(printf '%s' "$PAYLOAD" | b64url)"
SIGNATURE=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$PRIVATE_KEY" -binary | b64url)
JWT="$SIGNING_INPUT.$SIGNATURE"

echo "payload : $PAYLOAD" >&2
echo "expires : ${TTL}초 후" >&2
echo "$BASE_URL/api/issuance/session?JWT=$JWT"
