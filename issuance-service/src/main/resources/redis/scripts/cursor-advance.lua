-- 서빙 커서 조회 + 락 기반 피기백 증가 (플랜 4-3). 별도 스케줄러 없이 폴링 요청이 증가를 겸한다.
-- KEYS[1] advance-lock:{eventId}    윈도우 락 (윈도우당 1건만 SET NX 성공)
-- KEYS[2] cursor:serving:{eventId}  서빙 커서 (이벤트당 전역 값 1개)
-- ARGV[1] 윈도우 밀리초 (tetra.queue.cursor-window, 기본 3000)
-- ARGV[2] 윈도우당 증가량 (tetra.queue.cursor-step, 기본 300)
-- ARGV[3] 이벤트 시작 여부 "1"/"0" — 시작 전에는 증가시키지 않는다 (D10)
-- 반환: 현재 커서 (아직 없으면 0)

if ARGV[3] == '1' and redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) then
  redis.call('INCRBY', KEYS[2], ARGV[2])
end

return tonumber(redis.call('GET', KEYS[2]) or '0')
