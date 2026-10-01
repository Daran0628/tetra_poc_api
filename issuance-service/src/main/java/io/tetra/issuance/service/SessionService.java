package io.tetra.issuance.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.filter.VerifiedEntryToken;
import io.tetra.issuance.redis.EntryStore;
import io.tetra.issuance.redis.SessionStore;

/**
 * 세션 발급 (플랜 4-1 처리 순서 2~3). 서명·만료·테넌트 검증은 JwtAuthFilter 가 끝냈다.
 * MySQL 은 읽지 않는다 (Redis + 이벤트 메타 캐시만).
 */
@Service
public class SessionService {

	private final EntryStore entryStore;
	private final SessionStore sessionStore;
	private final TetraProperties properties;
	private final Clock clock;

	public SessionService(EntryStore entryStore, SessionStore sessionStore, TetraProperties properties, Clock clock) {
		this.entryStore = entryStore;
		this.sessionStore = sessionStore;
		this.properties = properties;
		this.clock = clock;
	}

	/** @return 세션 ID (기존 세션 재사용이면 그 ID) */
	public String enter(VerifiedEntryToken token) {
		// 2. jti 1회 사용: 토큰이 유효한 동안(시계 오차 허용분 포함)만 기록해 두면 된다
		Duration ttl = Duration.between(clock.instant(), token.expiresAt()).plus(properties.jwt().clockSkew());
		if (!entryStore.markTokenUsed(token.jti(), atLeastOneSecond(ttl))) {
			throw new BusinessException(ErrorCode.JWT_REUSED);
		}

		// 3. 같은 이벤트·사용자의 세션이 살아 있으면 재사용 (진입 카운터 증가 없음)
		var existing = sessionStore.findSessionIdByUser(token.eventId(), token.userId())
				.filter(sid -> sessionStore.find(sid).isPresent());
		if (existing.isPresent()) {
			return existing.get();
		}
		String sid = sessionStore.create(token.tenantId(), token.eventId(), token.userId(), properties.session().ttl());
		entryStore.incrementEntryCount(token.eventId());
		return sid;
	}

	private static Duration atLeastOneSecond(Duration d) {
		return d.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : d;
	}

}
