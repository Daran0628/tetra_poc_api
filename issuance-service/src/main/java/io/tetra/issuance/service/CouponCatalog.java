package io.tetra.issuance.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.domain.Coupon;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.repository.CouponRepository;

/**
 * 쿠폰 정보(이름·설명)를 기동 시 메모리에 두고, 같은 때 Redis 재고를 워밍업한다 (플랜 4-4).
 * <p>
 * 기동 = Issuance Service 프로세스 시작 (배포 환경은 Pod 가 뜰 때마다). 웹 서버가 요청을 받기 전에 실행된다.
 * 기본 if-absent 라 여러 Pod 가 각자 실행해도 처음 1번만 채워지고, 재시작·스케일아웃 때 차감분이 유지된다.
 * force 는 로컬 초기화 전용. Redis 데이터 유실 시 위험은 Next Plan N3.
 */
@Component
public class CouponCatalog implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(CouponCatalog.class);

	private final CouponRepository couponRepository;
	private final CouponStockStore stockStore;
	private final WarmupMode warmupMode;
	private volatile Map<Long, List<CouponInfo>> byEvent = Map.of();

	public CouponCatalog(CouponRepository couponRepository, CouponStockStore stockStore, TetraProperties properties) {
		this.couponRepository = couponRepository;
		this.stockStore = stockStore;
		this.warmupMode = properties.stock().warmupMode();
	}

	/** 화면에 보여줄 쿠폰 정보 + 신청 시점 기준 재고 (MySQL 값, 고정) */
	public record CouponInfo(long couponId, String name, String description, long initialStock) {
	}

	@Override
	public void afterPropertiesSet() {
		load();
		warmup(warmupMode);
	}

	public void load() {
		Map<Long, List<CouponInfo>> loaded = new LinkedHashMap<>();
		for (Coupon c : couponRepository.findAllByOrderByEventIdAscCouponIdAsc()) {
			loaded.computeIfAbsent(c.getEventId(), k -> new ArrayList<>())
					.add(new CouponInfo(c.getCouponId(), c.getName(), c.getDescription(), c.getStockCount()));
		}
		loaded.replaceAll((k, v) -> List.copyOf(v));
		byEvent = Map.copyOf(loaded);
	}

	/** MySQL coupon.stock_count → Redis coupon:stock:{eventId}:{couponId} */
	public void warmup(WarmupMode mode) {
		int written = 0;
		int total = 0;
		for (var entry : byEvent.entrySet()) {
			for (CouponInfo c : entry.getValue()) {
				total++;
				if (stockStore.putStock(entry.getKey(), c.couponId(), c.initialStock(), mode == WarmupMode.IF_ABSENT)) {
					written++;
				}
			}
		}
		log.info("Coupon stock warmup ({}): {} of {} keys written, {} kept", mode, written, total, total - written);
	}

	/** 이벤트의 쿠폰 목록 (couponId 순, 없으면 빈 목록) */
	public List<CouponInfo> coupons(long eventId) {
		return byEvent.getOrDefault(eventId, List.of());
	}

}
