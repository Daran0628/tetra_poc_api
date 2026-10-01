#!/usr/bin/env bash
# 로컬 이벤트 상태를 처음으로 되돌린다 (로컬 docker-compose 전용 — 배포 환경에서 쓰지 말 것).
#   1) Valkey 전체 비우기 (세션·jti·번호표·커서·재고·claim 표시)
#   2) MySQL issuance_history 비우기
#   3) 재고 다시 채우기 (MySQL coupon.stock_count → Redis coupon:stock:{eventId}:{couponId})
# 앱을 껐다 켤 필요 없음. 기존 세션은 모두 사라지므로 다시 입장해야 한다.
# 테이블 구조·시드·공개키까지 처음으로 되돌리려면:  docker compose down -v && docker compose up -d --wait
#
# 사용: PoC 폴더에서  bash scripts/reset-local.sh --yes
set -euo pipefail

if [[ "${1:-}" != "--yes" ]]; then
  echo "로컬 Redis 와 발급 이력을 모두 지웁니다. 계속하려면:  bash scripts/reset-local.sh --yes" >&2
  exit 1
fi

mysql_q() { docker exec tetra-mysql mysql -utetra -ptetra tetra_poc -N -e "$1" 2>/dev/null; }

docker exec tetra-valkey valkey-cli FLUSHDB >/dev/null
echo "Valkey 비움"

mysql_q "TRUNCATE TABLE issuance_history"
echo "issuance_history 비움"

count=0
while read -r event_id coupon_id stock; do
  [[ -z "$event_id" ]] && continue
  docker exec tetra-valkey valkey-cli SET "coupon:stock:$event_id:$coupon_id" "$stock" >/dev/null
  count=$((count + 1))
done < <(mysql_q "SELECT event_id, coupon_id, stock_count FROM coupon ORDER BY event_id, coupon_id" | tr -d '\r')
echo "재고 다시 채움: ${count}개 키"
