package io.tetra.issuance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.repository.IssuanceHistoryRepository;
import io.tetra.issuance.service.CouponCatalog.CouponInfo;

/** claim 의 시각 규칙(이벤트 종료, B1)을 고정 Clock 으로 확인. 재고·발급 동작 자체는 통합 테스트에서 확인. */
class ClaimServiceTest {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final LocalDateTime START = LocalDateTime.of(2026, 9, 29, 10, 0);
	static final LocalDateTime END = LocalDateTime.of(2026, 10, 29, 10, 0);
	static final IssuanceSession WITH_TICKET = new IssuanceSession("sid", "poctenant001", 1L, "user-0001", 7L,
			Instant.parse("2026-10-02T01:00:00Z"));
	static final IssuanceSession NO_TICKET = new IssuanceSession("sid", "poctenant001", 1L, "user-0001", null, null);

	final EventMetaCache events = mock(EventMetaCache.class);
	final CouponCatalog catalog = mock(CouponCatalog.class);
	final CouponStockStore stockStore = mock(CouponStockStore.class);
	final IssuanceHistoryRepository historyRepository = mock(IssuanceHistoryRepository.class);

	ClaimService serviceAt(LocalDateTime now) {
		when(events.find(1L)).thenReturn(Optional.of(new EventMeta(1L, "poctenant001", START, END, null)));
		when(catalog.coupons(1L)).thenReturn(List.of(new CouponInfo(1L, "PoC 쿠폰 1", "PoC 쿠폰입니다.", 10)));
		when(stockStore.claim(anyLong(), anyString(), anyList())).thenReturn(Optional.of(List.of(1L)));
		return new ClaimService(events, catalog, stockStore, historyRepository,
				Clock.fixed(now.atZone(KST).toInstant(), KST));
	}

	@Test
	void 종료_1초_전에는_발급한다() {
		ClaimService.ClaimResult result = serviceAt(END.minusSeconds(1)).claim(WITH_TICKET);
		assertThat(result.result()).isEqualTo(ClaimService.Result.SUCCESS);
		verify(historyRepository).save(any());
	}

	@Test
	void 종료_정각부터_409_EVENT_ENDED이고_재고와_이력을_건드리지_않는다() {
		for (LocalDateTime t : new LocalDateTime[] { END, END.plusHours(3) }) {
			ClaimService service = serviceAt(t);
			assertThatThrownBy(() -> service.claim(WITH_TICKET))
					.extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.EVENT_ENDED);
		}
		verify(stockStore, never()).claim(anyLong(), anyString(), anyList());
		verifyNoInteractions(historyRepository);
	}

	@Test
	void 종료_후에는_번호표가_없어도_EVENT_ENDED가_먼저다() {
		ClaimService service = serviceAt(END);
		assertThatThrownBy(() -> service.claim(NO_TICKET))
				.extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.EVENT_ENDED);
	}

	@Test
	void 이벤트_정보가_없으면_404() {
		ClaimService service = serviceAt(START);
		when(events.find(1L)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.claim(WITH_TICKET))
				.extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND);
	}

}
