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
		// 시작 전이면 Redis 를 부르기 전에 거절. start_at 은 KST 저장, Clock 도 KST
		if (LocalDateTime.now(clock).isBefore(event.startAt())) {
			throw new BusinessException(ErrorCode.EVENT_NOT_STARTED);
		}
		return ticketStore.issue(session.sessionId(), session.eventId(), clock.instant())
				.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
	}

}
