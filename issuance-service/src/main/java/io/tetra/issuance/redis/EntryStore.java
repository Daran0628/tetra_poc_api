package io.tetra.issuance.redis;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 입장 단계의 Redis 키 (플랜 4-1).
 * <pre>
 * jti:{jti}               "1", TTL = 토큰 만료까지  — 입장 토큰 1회 사용 보장
 * entry:count:{eventId}   신규 세션 수(진입 카운터) — 재진입(세션 재사용)은 세지 않음
 * </pre>
 */
@Component
public class EntryStore {

	private final StringRedisTemplate redis;

	public EntryStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * SET jti:{jti} 1 NX EX ttl. 처음 쓰는 토큰이면 true, 이미 쓴 토큰이면 false (재전송 공격).
	 * exp 가 지나지 않은 짧은 시간 안의 재전송은 exp 검사만으로 못 막기 때문.
	 */
	public boolean markTokenUsed(String jti, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("jti:" + jti, "1", ttl));
	}

	public long incrementEntryCount(long eventId) {
		Long count = redis.opsForValue().increment("entry:count:" + eventId);
		return count == null ? 0 : count;
	}

}
