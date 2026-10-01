package io.tetra.issuance.redis;

import static io.tetra.issuance.redis.SessionKeys.FIELD_EVENT_ID;
import static io.tetra.issuance.redis.SessionKeys.FIELD_QUEUE_ENTERED_AT;
import static io.tetra.issuance.redis.SessionKeys.FIELD_TENANT_ID;
import static io.tetra.issuance.redis.SessionKeys.FIELD_TICKET_NUMBER;
import static io.tetra.issuance.redis.SessionKeys.FIELD_USER_ID;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 세션 읽기·생성. */
@Component
public class SessionStore {

	private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

	private final StringRedisTemplate redis;

	public SessionStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 세션이 없거나 만료됐으면, 또는 필수 필드가 깨져 있으면 empty. */
	public Optional<IssuanceSession> find(String sid) {
		Map<Object, Object> hash = redis.opsForHash().entries(SessionKeys.sessionKey(sid));
		if (hash.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(new IssuanceSession(sid,
					required(hash, FIELD_TENANT_ID),
					Long.parseLong(required(hash, FIELD_EVENT_ID)),
					required(hash, FIELD_USER_ID),
					optionalLong(hash, FIELD_TICKET_NUMBER),
					toInstant(optionalLong(hash, FIELD_QUEUE_ENTERED_AT))));
		}
		catch (IllegalStateException | NumberFormatException e) {
			log.warn("Corrupted session ignored: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/** 같은 이벤트·사용자의 기존 세션 ID (역참조 키). 없거나 만료면 empty. */
	public Optional<String> findSessionIdByUser(long eventId, String userId) {
		return Optional.ofNullable(redis.opsForValue().get(SessionKeys.sessionIndexKey(eventId, userId)));
	}

	/**
	 * 새 세션을 만든다 (번호표 필드는 M4 번호표 발급 때 추가).
	 * 세션 해시와 역참조 키를 같은 TTL 로 둔다.
	 * 조회→생성이 원자적이지 않다 — 정상 사용자 전제(Next Plan N1).
	 */
	public String create(String tenantId, long eventId, String userId, Duration ttl) {
		String sid = SessionKeys.newSessionId();
		String key = SessionKeys.sessionKey(sid);
		redis.opsForHash().putAll(key, Map.of(
				FIELD_TENANT_ID, tenantId,
				FIELD_EVENT_ID, Long.toString(eventId),
				FIELD_USER_ID, userId));
		redis.expire(key, ttl);
		redis.opsForValue().set(SessionKeys.sessionIndexKey(eventId, userId), sid, ttl);
		return sid;
	}

	private static String required(Map<Object, Object> hash, String field) {
		Object value = hash.get(field);
		if (value == null || value.toString().isBlank()) {
			throw new IllegalStateException("missing field " + field);
		}
		return value.toString();
	}

	private static Long optionalLong(Map<Object, Object> hash, String field) {
		Object value = hash.get(field);
		return value == null ? null : Long.valueOf(value.toString());
	}

	private static Instant toInstant(Long epochMillis) {
		return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
	}

}
