package io.tetra.issuance.redis;

import java.time.Instant;
import java.util.List;

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

	/** 번호표 발급 결과. ISSUED 일 때만 number 가 의미 있다. */
	public record IssueResult(Status status, long number) {

		public enum Status {
			ISSUED,
			/** 세션이 그 사이 만료됨 */
			SESSION_GONE,
			/** 이미 claim 처리된 사용자 */
			ALREADY_CLAIMED
		}

	}

	/** 번호를 채번해 세션에 기록한다. 세션 만료·이미 claim 처리된 사용자면 번호를 쓰지 않는다. */
	public IssueResult issue(String sessionId, long eventId, String userId, Instant now) {
		Long number = redis.execute(ticketIssueScript,
				List.of(SessionKeys.sessionKey(sessionId), seqKey(eventId), totalKey(eventId), retryKey(eventId),
						CouponStockStore.claimDoneKey(eventId, userId)),
				Long.toString(now.toEpochMilli()));
		if (number == null || number == -1L) {
			return new IssueResult(IssueResult.Status.SESSION_GONE, 0);
		}
		if (number == -2L) {
			return new IssueResult(IssueResult.Status.ALREADY_CLAIMED, 0);
		}
		return new IssueResult(IssueResult.Status.ISSUED, number);
	}

}
