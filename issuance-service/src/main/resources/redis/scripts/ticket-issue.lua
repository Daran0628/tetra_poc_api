-- 번호표 발급 (플랜 4-2). Redis 왕복 1회로 원자적으로 처리한다.
-- KEYS[1] session:{sid}                  세션 해시 (SessionKeys)
-- KEYS[2] ticket:seq:{eventId}           번호 채번 카운터
-- KEYS[3] ticket:total:{eventId}         신규 발급 수 (그 세션의 첫 번호표)
-- KEYS[4] ticket:retry:{eventId}         재발급 수 (새로고침·재시도로 새 번호를 받은 횟수 = 버려진 번호 수)
-- KEYS[5] claim:done:{eventId}:{userId}  이미 claim 처리된 사용자 표시 (coupon-claim.lua 가 기록)
-- ARGV[1] 현재 시각 epoch 밀리초 → queue_entered_at (D9: 번호표 발급 시각, 재발급 시 갱신)
-- 반환: 발급된 번호 / -1 세션 없음(필터 통과 직후 만료) / -2 이미 claim 처리된 사용자
-- -1·-2 는 번호를 소모하지 않는다.

if redis.call('EXISTS', KEYS[1]) == 0 then
  return -1
end

-- 이미 claim(성공·품절)을 처리받은 사용자는 다시 기다릴 이유가 없다 (2026-10-02 결정)
if redis.call('EXISTS', KEYS[5]) == 1 then
  return -2
end

local reissue = redis.call('HEXISTS', KEYS[1], 'ticket_number') == 1
local number = redis.call('INCR', KEYS[2])

-- 재발급이면 이전 번호를 덮어쓴다 (이전 번호는 "구멍"으로 남고 복구하지 않음 — 의도된 동작)
redis.call('HSET', KEYS[1], 'ticket_number', number, 'queue_entered_at', ARGV[1])

if reissue then
  redis.call('INCR', KEYS[4])
else
  redis.call('INCR', KEYS[3])
end

return number
