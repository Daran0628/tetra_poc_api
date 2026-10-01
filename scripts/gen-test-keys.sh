#!/usr/bin/env bash
# 로컬 테스트용 JWT(RS256) 키페어를 만들고, 공개키로 db/init/03_local_test_key.sql 을 생성한다.
# - 개인키: 테스트 JWT 서명용 (local-keys/, git 제외)
# - 공개키: event.public_key 에 UPDATE 되어 세션 API의 JWT 검증에 사용 (M0-1 S1, A 방식)
# 사용: PoC 폴더에서  bash scripts/gen-test-keys.sh   (이미 키가 있으면 덮어쓰지 않음, --force 로 재생성)
set -euo pipefail

POC_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="$POC_DIR/local-keys"
PRIVATE_KEY="$KEY_DIR/test-jwt-private.pem"
PUBLIC_KEY="$KEY_DIR/test-jwt-public.pem"
SQL_FILE="$POC_DIR/db/init/03_local_test_key.sql"

if [[ -f "$PRIVATE_KEY" && "${1:-}" != "--force" ]]; then
  echo "이미 키가 있습니다: $PRIVATE_KEY (재생성하려면 --force)"
else
  mkdir -p "$KEY_DIR"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY"
  openssl pkey -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY"
  echo "키페어 생성: $KEY_DIR"
fi

PUBLIC_PEM="$(cat "$PUBLIC_KEY")"
cat > "$SQL_FILE" <<SQL
-- 로컬 전용: scripts/gen-test-keys.sh 가 생성한 파일 (직접 수정하지 말 것)
-- Seed 원본(02_seed.sql)은 그대로 두고, 테스트 공개키만 event.public_key 에 넣는다.
USE tetra_poc;
UPDATE event SET public_key = '${PUBLIC_PEM}' WHERE event_id = 1;
SQL
echo "SQL 생성: $SQL_FILE"
