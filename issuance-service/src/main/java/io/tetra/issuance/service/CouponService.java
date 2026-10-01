package io.tetra.issuance.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.service.CouponCatalog.CouponInfo;

/**
 * 쿠폰 목록 + 잔여 수량 (플랜 4-4). 잔여 수량은 반드시 Redis 에서 읽는다 —
 * MySQL coupon.stock_count 는 초기값일 뿐 차감되지 않으므로 거기서 읽으면 항상 "10장 남음".
 */
@Service
public class CouponService {

	private final CouponCatalog catalog;
	private final CouponStockStore stockStore;

	public CouponService(CouponCatalog catalog, CouponStockStore stockStore) {
		this.catalog = catalog;
		this.stockStore = stockStore;
	}

	public record CouponView(long couponId, String name, String description, long remaining) {
	}

	public List<CouponView> list(long eventId) {
		List<CouponInfo> coupons = catalog.coupons(eventId);
		if (coupons.isEmpty()) {
			return List.of();
		}
		List<Long> remaining = stockStore.remaining(eventId, coupons.stream().map(CouponInfo::couponId).toList());
		List<CouponView> views = new ArrayList<>(coupons.size());
		for (int i = 0; i < coupons.size(); i++) {
			CouponInfo c = coupons.get(i);
			views.add(new CouponView(c.couponId(), c.name(), c.description(), remaining.get(i)));
		}
		return views;
	}

}
