package io.tetra.issuance.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.redis.CursorStore;

/**
 * 서빙 커서 (플랜 4-3). 인증 없음 — 커서는 이벤트 공통 비민감 값이고 CDN 캐시 대상.
 * 증가는 락 기반 피기백: 윈도우(3초)마다 첫 요청 1건만 step(300) 올린다 = 초당 100명.
 * CDN 캐시 히트는 오리진에 닿지 않으므로 증가는 캐시 미스 요청에서만 일어난다 (엣지마다 초당 1회 수준이라 충분).
 */
@Service
public class QueueCursorService {

	private final EventMetaCache events;
	private final CursorStore cursorStore;
	private final TetraProperties.Queue config;
	private final Clock clock;

	public QueueCursorService(EventMetaCache events, CursorStore cursorStore, TetraProperties properties, Clock clock) {
		this.events = events;
		this.cursorStore = cursorStore;
		this.config = properties.queue();
		this.clock = clock;
	}

	public long current(long eventId) {
		EventMeta event = events.find(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
		boolean started = !LocalDateTime.now(clock).isBefore(event.startAt()); // 시작 전에는 증가 없음 (D10)
		return cursorStore.advanceAndGet(eventId, started, config.cursorWindow(), config.cursorStep());
	}

}
