package io.tetra.issuance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.redis.TicketStore;

/** 시각에 따라 달라지는 규칙(이벤트 시작 전 거절)을 고정 Clock 으로 확인. */
class TicketServiceTest {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final LocalDateTime START = LocalDateTime.of(2026, 9, 29, 10, 0);
	static final IssuanceSession SESSION = new IssuanceSession("sid", "poctenant001", 1L, "user-0001", null, null);

	final EventMetaCache events = mock(EventMetaCache.class);
	final TicketStore ticketStore = mock(TicketStore.class);

	TicketService serviceAt(LocalDateTime now) {
		when(events.find(1L)).thenReturn(Optional.of(new EventMeta(1L, "poctenant001", START, null)));
		when(ticketStore.issue(anyString(), anyLong(), any())).thenReturn(OptionalLong.of(7L));
		return new TicketService(events, ticketStore, Clock.fixed(now.atZone(KST).toInstant(), KST));
	}

	@Test
	void 시작_1초_전이면_409이고_Redis를_부르지_않는다() {
		TicketService service = serviceAt(START.minusSeconds(1));
		assertThatThrownBy(() -> service.issue(SESSION))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.EVENT_NOT_STARTED);
		verify(ticketStore, never()).issue(anyString(), anyLong(), any());
	}

	@Test
	void 시작_시각_정각부터_발급한다() {
		assertThat(serviceAt(START).issue(SESSION)).isEqualTo(7L);
	}

	@Test
	void 세션이_그_사이_만료됐으면_401() {
		TicketService service = serviceAt(START.plusMinutes(1));
		when(ticketStore.issue(anyString(), anyLong(), any())).thenReturn(OptionalLong.empty());
		assertThatThrownBy(() -> service.issue(SESSION))
				.extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND);
	}

}
