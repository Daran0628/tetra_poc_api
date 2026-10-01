-- 쿠폰 일괄 발급 (플랜 4-5, D8). 원자적으로 처리해 초과 발급·중복 발급을 막는다.
-- KEYS[1]      claim:done:{eventId}:{userId}       이미 claim 한 사용자 표시 (성공·품절 모두)
-- KEYS[2..n]   coupon:stock:{eventId}:{couponId}   쿠폰 종류별 Redis 재고
-- ARGV[1..n-1] KEYS[2..n] 에 대응하는 couponId
-- 반환: 발급된 couponId 목록 (재고가 남은 종류마다 1장씩). 빈 목록 = 품절. {-1} = 이미 claim 한 사용자

if not redis.call('SET', KEYS[1], '1', 'NX') then
  return { -1 }
end

local issued = {}
for i = 2, #KEYS do
  local stock = tonumber(redis.call('GET', KEYS[i]) or '0')
  if stock > 0 then
    redis.call('DECR', KEYS[i])
    issued[#issued + 1] = tonumber(ARGV[i - 1])
  end
end

return issued
