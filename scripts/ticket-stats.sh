#!/usr/bin/env bash
# 번호표 통계 — 구멍 비율 (플랜 5-5). 로컬 docker-compose 의 Valkey 를 읽는다.
#   total : 신규 발급 수 (번호표를 받은 세션 수)
#   retry : 재발급 수 (새로고침·재시도로 이전 번호를 버린 횟수 = 구멍 수)
#   구멍 비율          = retry ÷ (total + retry)   전체 발급 번호 중 버려진 번호 비율
#   1인당 재발급 횟수  = retry ÷ total
# 사용: PoC 폴더에서  bash scripts/ticket-stats.sh [event_id]   (기본 1)
set -euo pipefail

EVENT_ID="${1:-1}"
get() { docker exec tetra-valkey valkey-cli GET "$1" | tr -d '\r'; }

SEQ=$(get "ticket:seq:$EVENT_ID");     SEQ=${SEQ:-0}
TOTAL=$(get "ticket:total:$EVENT_ID"); TOTAL=${TOTAL:-0}
RETRY=$(get "ticket:retry:$EVENT_ID"); RETRY=${RETRY:-0}
ENTRY=$(get "entry:count:$EVENT_ID");  ENTRY=${ENTRY:-0}

awk -v e="$EVENT_ID" -v seq="$SEQ" -v t="$TOTAL" -v r="$RETRY" -v en="$ENTRY" 'BEGIN {
  printf "event %s\n", e
  printf "  진입(신규 세션)      : %d\n", en
  printf "  마지막 번호(seq)     : %d\n", seq
  printf "  신규 발급(total)     : %d\n", t
  printf "  재발급(retry)        : %d\n", r
  if (t + r > 0) printf "  구멍 비율            : %.1f%%  (retry / (total+retry))\n", 100 * r / (t + r)
  if (t > 0)     printf "  1인당 재발급 횟수    : %.2f   (retry / total)\n", r / t
}'
