package io.tetra.issuance.redis;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 서빙 커서 Redis 키.
 * <pre>
 * cursor:serving:{eventId}   서빙 커서
 * advance-lock:{eventId}     증가 윈도우 락 (TTL = cursor-window)
 * </pre>
 */
@Component
public class CursorStore {

	private final StringRedisTemplate redis;
	private final RedisScript<Long> cursorAdvanceScript;

	public CursorStore(StringRedisTemplate redis, @Qualifier("cursorAdvanceScript") RedisScript<Long> cursorAdvanceScript) {
		this.redis = redis;
		this.cursorAdvanceScript = cursorAdvanceScript;
	}

	public static String cursorKey(long eventId) {
		return "cursor:serving:" + eventId;
	}

	public static String lockKey(long eventId) {
		return "advance-lock:" + eventId;
	}

	/** 윈도우의 첫 요청이면 커서를 step 만큼 올리고(시작 후에만), 현재 커서를 돌려준다. */
	public long advanceAndGet(long eventId, boolean started, Duration window, int step) {
		Long cursor = redis.execute(cursorAdvanceScript, List.of(lockKey(eventId), cursorKey(eventId)),
				Long.toString(window.toMillis()), Integer.toString(step), started ? "1" : "0");
		return cursor == null ? 0 : cursor;
	}

}
