package io.tetra.issuance.redis;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Redis 세션 키·필드 이름과 세션 ID 규칙 (D5). Lua 스크립트(M4·M6)도 같은 이름을 쓴다.
 *
 * <pre>
 * session:{sid}                    Hash, TTL = tetra.session.ttl
 *   tenant_id        "poctenant001"
 *   event_id         "1"
 *   user_id          "user-0001"
 *   ticket_number    "1234"            ← 번호표 발급(M4) 전에는 없음
 *   queue_entered_at "1790000000000"   ← 번호표 발급 시각, epoch 밀리초 (D9). 발급 전에는 없음
 * session:idx:{eventId}:{userId}   String = sid (같은 사용자 재진입 시 세션 재사용, M3)
 * </pre>
 */
public final class SessionKeys {

	public static final String FIELD_TENANT_ID = "tenant_id";
	public static final String FIELD_EVENT_ID = "event_id";
	public static final String FIELD_USER_ID = "user_id";
	public static final String FIELD_TICKET_NUMBER = "ticket_number";
	public static final String FIELD_QUEUE_ENTERED_AT = "queue_entered_at";

	/** 세션 ID: 256비트 난수의 URL-safe Base64 (패딩 없음, 43자) */
	private static final Pattern SID_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final SecureRandom RANDOM = new SecureRandom();

	private SessionKeys() {
	}

	public static String sessionKey(String sid) {
		return "session:" + sid;
	}

	public static String sessionIndexKey(long eventId, String userId) {
		return "session:idx:" + eventId + ":" + userId;
	}

	/** 새 세션 ID (추측 불가능한 난수) */
	public static String newSessionId() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 쿠키 값이 세션 ID 형식인지. 형식이 틀리면 Redis 를 조회하지 않는다. */
	public static boolean isValidSessionId(String sid) {
		return sid != null && SID_FORMAT.matcher(sid).matches();
	}

}
