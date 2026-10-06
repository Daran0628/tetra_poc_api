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
 * claim:done:{eventId}:{userId}       claim 처리 결과 = 발급된 couponId 쉼표 목록, 품절이면 "" (TTL 없음 — Next Plan N4). 번호표 발급도 이 키를 보고 막는다
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

	/** claim 결과. replay = 이미 처리된 사용자라 저장된 처음 결과를 돌려준 것. issued 가 비었으면 품절 */
	public record ClaimOutcome(boolean replay, List<Long> issued) {
	}

	/**
	 * 원자적 claim. 처음이면 재고를 차감하고 결과를 claim:done 에 저장, 이미 처리된 사용자면 저장된 결과를 그대로 돌려준다.
	 */
	public ClaimOutcome claim(long eventId, String userId, List<Long> couponIds) {
		List<String> keys = new ArrayList<>(couponIds.size() + 1);
		keys.add(claimDoneKey(eventId, userId));
		couponIds.forEach(id -> keys.add(stockKey(eventId, id)));
		Object[] args = couponIds.stream().map(String::valueOf).toArray();

		List<Long> reply = redis.execute(couponClaimScript, keys, args);
		if (reply == null || reply.isEmpty()) {
			throw new IllegalStateException("coupon-claim.lua returned no result");
		}
		return new ClaimOutcome(reply.get(0) == 1L, List.copyOf(reply.subList(1, reply.size())));
	}

}
