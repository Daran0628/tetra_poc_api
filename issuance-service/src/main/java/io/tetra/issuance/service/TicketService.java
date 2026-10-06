package io.tetra.issuance.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.redis.TicketStore;

/** 번호표 발급 (플랜 4-2). MySQL 은 읽지 않는다. */
@Service
public class TicketService {

	private final EventMetaCache events;
	private final TicketStore ticketStore;
	private final Clock clock;

	public TicketService(EventMetaCache events, TicketStore ticketStore, Clock clock) {
		this.events = events;
		this.ticketStore = ticketStore;
		this.clock = clock;
	}

	/** 세션 필터가 경로 eventId 와 세션 event_id 일치를 이미 확인했다. 재호출 시 매번 새 번호. */
	public long issue(IssuanceSession session) {
		EventMeta event = events.find(session.eventId())
				.orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
		// 시작 전·종료 후면 Redis 를 부르기 전에 거절. start_at·end_at 은 KST 저장, Clock 도 KST
		LocalDateTime now = LocalDateTime.now(clock);
		if (!event.hasStarted(now)) {
			throw new BusinessException(ErrorCode.EVENT_NOT_STARTED);
		}
		if (event.hasEnded(now)) {
			throw new BusinessException(ErrorCode.EVENT_ENDED);
		}
		TicketStore.IssueResult result = ticketStore.issue(session.sessionId(), session.eventId(), session.userId(),
				clock.instant());
		return switch (result.status()) {
			case ISSUED -> result.number();
			case SESSION_GONE -> throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
			// 이미 claim(성공·품절)을 처리받은 사용자는 다시 기다리지 않게 번호표 단계에서 막는다 (2026-10-02)
			case ALREADY_CLAIMED -> throw new BusinessException(ErrorCode.ALREADY_CLAIMED);
		};
	}

}
