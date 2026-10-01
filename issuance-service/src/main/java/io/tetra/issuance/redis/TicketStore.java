package io.tetra.issuance.redis;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 번호표 Redis 키.
 * <pre>
 * ticket:seq:{eventId}     마지막으로 발급된 번호
 * ticket:total:{eventId}   신규 발급 수
 * ticket:retry:{eventId}   재발급 수 (= 버려진 번호 수)
 * </pre>
 */
@Component
public class TicketStore {

	private final StringRedisTemplate redis;
	private final RedisScript<Long> ticketIssueScript;

	public TicketStore(StringRedisTemplate redis, @Qualifier("ticketIssueScript") RedisScript<Long> ticketIssueScript) {
		this.redis = redis;
		this.ticketIssueScript = ticketIssueScript;
	}

	public static String seqKey(long eventId) {
		return "ticket:seq:" + eventId;
	}

	public static String totalKey(long eventId) {
		return "ticket:total:" + eventId;
	}

	public static String retryKey(long eventId) {
		return "ticket:retry:" + eventId;
	}

	/** 번호를 채번해 세션에 기록한다. 세션이 그 사이 만료됐으면 empty. */
	public OptionalLong issue(String sessionId, long eventId, Instant now) {
		Long number = redis.execute(ticketIssueScript,
				List.of(SessionKeys.sessionKey(sessionId), seqKey(eventId), totalKey(eventId), retryKey(eventId)),
				Long.toString(now.toEpochMilli()));
		return number == null || number < 0 ? OptionalLong.empty() : OptionalLong.of(number);
	}

}
