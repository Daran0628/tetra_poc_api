-- 쿠폰 일괄 발급 (플랜 4-5, D8). 원자적으로 처리해 초과 발급·중복 발급을 막는다.
-- KEYS[1]      claim:done:{eventId}:{userId}       claim 처리 결과 (성공·품절 모두). 값 = 발급된 couponId 쉼표 목록, 품절이면 ""
-- KEYS[2..n]   coupon:stock:{eventId}:{couponId}   쿠폰 종류별 Redis 재고
-- ARGV[1..n-1] KEYS[2..n] 에 대응하는 couponId
-- 반환: { 처음 처리 0 | 이전 결과 1, 발급된 couponId... }  (couponId 가 없으면 품절)
-- 이미 처리된 사용자가 다시 claim 하면 재고는 건드리지 않고 저장된 처음 결과를 그대로 돌려준다
-- (응답을 못 받은 사용자가 다시 요청해도 같은 결과 — 인프라 문서 B3)

local done = redis.call('GET', KEYS[1])
if done then
  local replay = { 1 }
  for id in string.gmatch(done, '%d+') do
    replay[#replay + 1] = tonumber(id)
  end
  return replay
end

local issued = {}
for i = 2, #KEYS do
  local stock = tonumber(redis.call('GET', KEYS[i]) or '0')
  if stock > 0 then
    redis.call('DECR', KEYS[i])
    issued[#issued + 1] = tonumber(ARGV[i - 1])
  end
end
redis.call('SET', KEYS[1], table.concat(issued, ','))

local result = { 0 }
for _, id in ipairs(issued) do
  result[#result + 1] = id
end
return result
