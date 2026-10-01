#!/usr/bin/env bash
# 로컬 앱에 가상 사용자 N명을 흘려 전체 흐름을 확인한다 (기능 확인용 — 부하테스트 도구가 아님).
#   입장(JWT → 세션) → 번호표 → 커서 폴링(받은 커서 최댓값 ≥ 내 번호) → claim
# 끝나면 결과 집계·번호표 통계·발급 이력 건수를 출력한다.
# 사용: PoC 폴더에서 앱을 띄운 뒤  bash scripts/simulate-users.sh [N=200] [base_url=http://localhost:8080]
#       깨끗한 상태에서 보려면 먼저  bash scripts/reset-local.sh --yes
set -euo pipefail

N="${1:-200}"
BASE_URL="${2:-http://localhost:8080}"
EVENT_API="$BASE_URL/api/issuance/events/1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_ID=$(openssl rand -hex 3)

declare -a SIDS TICKETS RESULTS
echo "입장·번호표: ${N}명 (run $RUN_ID)"
for ((i = 0; i < N; i++)); do
  url=$(bash "$SCRIPT_DIR/issue-test-jwt.sh" "sim-$RUN_ID-$i" 1 poctenant001 90 "$BASE_URL" 2>/dev/null)
  SIDS[i]=$(curl -s -i "$url" | grep -o "TETRA_SID=[^;]*" | cut -d= -f2)
  TICKETS[i]=$(curl -s -X POST --cookie "TETRA_SID=${SIDS[i]}" "$EVENT_API/ticket" | grep -o '"ticketNumber":[0-9]*' | cut -d: -f2)
  RESULTS[i]=""
done

echo "폴링·claim (커서가 내 번호에 닿은 사람부터)"
max_cursor=0
remaining=$N
while ((remaining > 0)); do
  cursor=$(curl -s "$EVENT_API/queue/cursor" | grep -o '"cursor":[0-9]*' | cut -d: -f2)
  ((cursor > max_cursor)) && max_cursor=$cursor   # 받은 값 중 최댓값만 사용 (폴링 스펙)
  for ((i = 0; i < N; i++)); do
    if [[ -z "${RESULTS[i]}" ]] && ((TICKETS[i] <= max_cursor)); then
      RESULTS[i]=$(curl -s -X POST --cookie "TETRA_SID=${SIDS[i]}" "$EVENT_API/coupons/claim" \
        | grep -o '"result":"[A-Z_]*"\|"code":"[A-Z_]*"' | head -1 | cut -d'"' -f4)
      remaining=$((remaining - 1))
    fi
  done
  ((remaining > 0)) && sleep 1
done

success=0; soldout=0; other=0
for r in "${RESULTS[@]}"; do
  case "$r" in SUCCESS) success=$((success + 1)) ;; SOLD_OUT) soldout=$((soldout + 1)) ;; *) other=$((other + 1)) ;; esac
done
echo
echo "claim 결과: SUCCESS $success / SOLD_OUT $soldout / 기타 $other  (최종 커서 $max_cursor)"
bash "$SCRIPT_DIR/ticket-stats.sh" 1
docker exec tetra-mysql mysql -utetra -ptetra tetra_poc -e \
  "SELECT result, COUNT(*) AS rows_ FROM issuance_history GROUP BY result" 2>/dev/null
echo -n "Redis 남은 재고 합계: "
total=0
for id in $(seq 1 10); do v=$(docker exec tetra-valkey valkey-cli GET "coupon:stock:1:$id" | tr -d '\r'); total=$((total + ${v:-0})); done
echo "$total"
