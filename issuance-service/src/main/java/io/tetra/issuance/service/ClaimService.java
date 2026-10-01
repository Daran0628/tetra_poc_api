package io.tetra.issuance.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.tetra.issuance.common.BusinessException;
import io.tetra.issuance.common.ErrorCode;
import io.tetra.issuance.domain.IssuanceHistory;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.repository.IssuanceHistoryRepository;
import io.tetra.issuance.service.CouponCatalog.CouponInfo;

/**
 * 쿠폰 발급 (플랜 4-5). 입장 자격(번호 순서) 재검증은 PoC 범위에서 생략 — 재고 Lua 가 초과 발급을 막는다.
 * <ol>
 * <li>번호표 없는 세션이면 409 TICKET_REQUIRED (queue_entered_at 이 없어 이력을 남길 수 없음, M0-3)</li>
 * <li>Lua: 중복 claim 차단 + 재고가 남은 종류마다 1장씩 차감 (D8)</li>
 * <li>결과 확정 즉시 issuance_history 1행 INSERT (성공·품절 모두, served_at NULL)</li>
 * </ol>
 * Redis 차감 후 INSERT 가 실패해도 발급 결과는 그대로 응답하고 에러 로그만 남긴다 (PoC 방침, ADR-0002 트레이드오프).
 */
@Service
public class ClaimService {

	private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

	private final CouponCatalog catalog;
	private final CouponStockStore stockStore;
	private final IssuanceHistoryRepository historyRepository;
	private final Clock clock;

	public ClaimService(CouponCatalog catalog, CouponStockStore stockStore, IssuanceHistoryRepository historyRepository,
			Clock clock) {
		this.catalog = catalog;
		this.stockStore = stockStore;
		this.historyRepository = historyRepository;
		this.clock = clock;
	}

	public enum Result {
		SUCCESS, SOLD_OUT
	}

	public record IssuedCoupon(long couponId, String name, String description) {
	}

	public record ClaimResult(Result result, List<IssuedCoupon> coupons) {
	}

	public ClaimResult claim(IssuanceSession session) {
		if (!session.hasTicket()) {
			throw new BusinessException(ErrorCode.TICKET_REQUIRED);
		}
		List<CouponInfo> coupons = catalog.coupons(session.eventId());
		List<Long> issuedIds = stockStore
				.claim(session.eventId(), session.userId(), coupons.stream().map(CouponInfo::couponId).toList())
				.orElseThrow(() -> new BusinessException(ErrorCode.ALREADY_CLAIMED));

		Result result = issuedIds.isEmpty() ? Result.SOLD_OUT : Result.SUCCESS;
		recordHistory(session, result);

		Map<Long, CouponInfo> byId = coupons.stream().collect(Collectors.toMap(CouponInfo::couponId, Function.identity()));
		List<IssuedCoupon> issued = issuedIds.stream()
				.map(byId::get)
				.map(c -> new IssuedCoupon(c.couponId(), c.name(), c.description()))
				.toList();
		return new ClaimResult(result, issued);
	}

	private void recordHistory(IssuanceSession session, Result result) {
		var history = new IssuanceHistory(session.tenantId(), session.eventId(), session.userId(),
				session.ticketNumber(), LocalDateTime.ofInstant(session.queueEnteredAt(), clock.getZone()),
				result == Result.SUCCESS ? IssuanceHistory.RESULT_SUCCESS : IssuanceHistory.RESULT_FAILED_SOLDOUT);
		try {
			historyRepository.save(history);
		}
		catch (DataIntegrityViolationException e) {
			// (event_id, user_id) UNIQUE — 이미 기록됨. 평소엔 claim:done 이 먼저 막으므로 재시도·키 유실 때만 발생
			log.info("Issuance history already recorded: event={} user={}", session.eventId(), session.userId());
		}
		catch (DataAccessException e) {
			log.error("Issuance history INSERT failed (Redis already decided): event={} user={} result={}",
					session.eventId(), session.userId(), result, e);
		}
	}

}
