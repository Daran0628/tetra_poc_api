package io.tetra.issuance.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 재고·claim Redis 키.
 * <pre>
 * coupon:stock:{eventId}:{couponId}   종류별 재고 (워밍업 때 coupon.stock_count 로 채움, claim 때만 DECR)
 * claim:done:{eventId}:{userId}       claim 처리된 사용자 (성공·품절 모두, TTL 없음 — Next Plan N4). 번호표 발급도 이 키를 보고 막는다
 * </pre>
 */
@Component
public class CouponStockStore {

	private final StringRedisTemplate redis;
	private final RedisScript<List<Long>> couponClaimScript;

	public CouponStockStore(StringRedisTemplate redis,
			@Qualifier("couponClaimScript") RedisScript<List<Long>> couponClaimScript) {
		this.redis = redis;
		this.couponClaimScript = couponClaimScript;
	}

	public static String stockKey(long eventId, long couponId) {
		return "coupon:stock:" + eventId + ":" + couponId;
	}

	public static String claimDoneKey(long eventId, String userId) {
		return "claim:done:" + eventId + ":" + userId;
	}

	/** 재고 워밍업. ifAbsent=true 면 키가 없을 때만 채운다(재시작해도 차감분 유지). @return 실제로 값을 넣었는지 */
	public boolean putStock(long eventId, long couponId, long stock, boolean ifAbsent) {
		String key = stockKey(eventId, couponId);
		if (ifAbsent) {
			return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, Long.toString(stock)));
		}
		redis.opsForValue().set(key, Long.toString(stock));
		return true;
	}

	/** 종류별 남은 재고 (couponIds 순서대로, 키가 없으면 0). */
	public List<Long> remaining(long eventId, List<Long> couponIds) {
		List<String> keys = couponIds.stream().map(id -> stockKey(eventId, id)).toList();
		List<String> values = Optional.ofNullable(redis.opsForValue().multiGet(keys)).orElse(List.of());
		List<Long> result = new ArrayList<>(couponIds.size());
		for (int i = 0; i < couponIds.size(); i++) {
			String v = i < values.size() ? values.get(i) : null;
			result.add(v == null ? 0L : Math.max(0L, Long.parseLong(v)));
		}
		return result;
	}

	/**
	 * 원자적 claim. @return empty = 이미 claim 한 사용자, 빈 목록 = 품절, 그 외 발급된 couponId 목록
	 */
	public Optional<List<Long>> claim(long eventId, String userId, List<Long> couponIds) {
		List<String> keys = new ArrayList<>(couponIds.size() + 1);
		keys.add(claimDoneKey(eventId, userId));
		couponIds.forEach(id -> keys.add(stockKey(eventId, id)));
		Object[] args = couponIds.stream().map(String::valueOf).toArray();

		List<Long> issued = redis.execute(couponClaimScript, keys, args);
		if (issued != null && issued.size() == 1 && issued.get(0) == -1L) {
			return Optional.empty();
		}
		return Optional.of(issued == null ? List.of() : List.copyOf(issued));
	}

}
