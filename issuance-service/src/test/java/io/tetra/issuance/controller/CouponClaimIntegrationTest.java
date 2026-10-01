package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.SessionStore;
import io.tetra.issuance.service.CouponCatalog;
import jakarta.servlet.http.Cookie;

/** 4-4 쿠폰 목록 · 4-5 claim — 실제 MySQL·Redis·Lua. 시드: 쿠폰 10종 × 10장 = 100장. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CouponClaimIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	SessionStore sessionStore;

	@Autowired
	CouponCatalog catalog;

	@BeforeEach
	void 재고와_이력을_처음_상태로() {
		catalog.warmup(WarmupMode.FORCE); // 종류별 10장
		jdbcTemplate.update("DELETE FROM issuance_history");
	}

	/** 세션 생성 + 번호표 발급까지 마친 사용자 */
	record User(String userId, String sid) {
	}

	User userWithTicket() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		String sid = sessionStore.create("poctenant001", 1L, userId, Duration.ofMinutes(10));
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie("TETRA_SID", sid)))
				.andExpect(status().isOk());
		return new User(userId, sid);
	}

	String claim(User user) throws Exception {
		return mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(new Cookie("TETRA_SID", user.sid())))
				.andReturn().getResponse().getContentAsString();
	}

	void setAllStock(long value) {
		for (long id = 1; id <= 10; id++) {
			redis.opsForValue().set(CouponStockStore.stockKey(1, id), Long.toString(value));
		}
	}

	Map<String, Object> historyOf(String userId) {
		return jdbcTemplate.queryForMap("SELECT * FROM issuance_history WHERE user_id = ?", userId);
	}

	// --- 워밍업 · 목록 -----------------------------------------------------------

	@Test
	void 워밍업_if_absent는_이미_있는_재고를_덮어쓰지_않고_force는_덮어쓴다() {
		redis.opsForValue().set(CouponStockStore.stockKey(1, 1), "3");
		catalog.warmup(WarmupMode.IF_ABSENT);
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("3");
		catalog.warmup(WarmupMode.FORCE);
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("10");
	}

	@Test
	void 쿠폰_목록은_10종이고_잔여_수량은_Redis_기준() throws Exception {
		User user = userWithTicket();
		redis.opsForValue().set(CouponStockStore.stockKey(1, 3), "4");

		mockMvc.perform(get("/api/issuance/events/1/coupons").cookie(new Cookie("TETRA_SID", user.sid())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.coupons.length()").value(10))
				.andExpect(jsonPath("$.data.coupons[0].couponId").value(1))
				.andExpect(jsonPath("$.data.coupons[0].name").value("PoC 쿠폰 1"))
				.andExpect(jsonPath("$.data.coupons[0].description").value("PoC 쿠폰입니다."))
				.andExpect(jsonPath("$.data.coupons[0].remaining").value(10))
				.andExpect(jsonPath("$.data.coupons[2].remaining").value(4)); // MySQL 은 여전히 10
		assertThat(jdbcTemplate.queryForObject("SELECT stock_count FROM coupon WHERE coupon_id = 3", Integer.class))
				.isEqualTo(10);
	}

	// --- claim ---------------------------------------------------------------

	@Test
	void 재고가_있으면_종류마다_1장씩_발급하고_이력_SUCCESS_1행() throws Exception {
		User user = userWithTicket();
		String body = claim(user);

		assertThat((String) JsonPath.read(body, "$.data.result")).isEqualTo("SUCCESS");
		assertThat((List<?>) JsonPath.read(body, "$.data.coupons")).hasSize(10);
		assertThat((String) JsonPath.read(body, "$.data.coupons[0].name")).isEqualTo("PoC 쿠폰 1");
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("9");

		Map<String, Object> row = historyOf(user.userId());
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(row.get("tenant_id")).isEqualTo("poctenant001");
		assertThat(row.get("served_at")).isNull();
		assertThat(row.get("ticket_number")).isNotNull();
		assertThat(row.get("queue_entered_at")).isNotNull();
		assertThat(String.valueOf(row.get("ticket_number")))
				.isEqualTo(redis.opsForHash().get("session:" + user.sid(), "ticket_number"));
	}

	@Test
	void 일부_종류만_남았으면_남은_종류만_발급() throws Exception {
		setAllStock(0);
		redis.opsForValue().set(CouponStockStore.stockKey(1, 7), "1");
		String body = claim(userWithTicket());

		assertThat((String) JsonPath.read(body, "$.data.result")).isEqualTo("SUCCESS");
		assertThat((List<Integer>) JsonPath.read(body, "$.data.coupons[*].couponId")).containsExactly(7);
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 7))).isEqualTo("0");
	}

	@Test
	void 모두_소진됐으면_200_SOLD_OUT이고_이력_FAILED_SOLDOUT() throws Exception {
		setAllStock(0);
		User user = userWithTicket();

		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(new Cookie("TETRA_SID", user.sid())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.result").value("SOLD_OUT"))
				.andExpect(jsonPath("$.data.coupons.length()").value(0));
		assertThat(historyOf(user.userId()).get("result")).isEqualTo("FAILED_SOLDOUT");
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("0"); // 음수 안 됨
	}

	@Test
	void 같은_사용자가_두_번_claim하면_두_번째는_409이고_재고와_이력이_늘지_않는다() throws Exception {
		User user = userWithTicket();
		claim(user);

		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(new Cookie("TETRA_SID", user.sid())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ALREADY_CLAIMED"));
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("9");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history WHERE user_id = ?",
				Integer.class, user.userId())).isEqualTo(1);
	}

	@Test
	void 번호표_없이_claim하면_409이고_아무것도_바뀌지_않는다() throws Exception {
		String userId = "user-" + UUID.randomUUID();
		String sid = sessionStore.create("poctenant001", 1L, userId, Duration.ofMinutes(10));

		mockMvc.perform(post("/api/issuance/events/1/coupons/claim").cookie(new Cookie("TETRA_SID", sid)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("TICKET_REQUIRED"));
		assertThat(redis.hasKey(CouponStockStore.claimDoneKey(1, userId))).isFalse();
		assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, 1))).isEqualTo("10");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class)).isZero();
	}

	@Test
	void 이력이_이미_있으면_중복_키_오류를_무시하고_정상_응답() throws Exception {
		User user = userWithTicket();
		jdbcTemplate.update("INSERT INTO issuance_history (tenant_id, event_id, user_id, queue_entered_at, result) "
				+ "VALUES ('poctenant001', 1, ?, NOW(3), 'SUCCESS')", user.userId()); // claim:done 유실 상황 흉내

		String body = claim(user);
		assertThat((String) JsonPath.read(body, "$.data.result")).isEqualTo("SUCCESS");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history WHERE user_id = ?",
				Integer.class, user.userId())).isEqualTo(1);
	}

	@Test
	void 동시에_150명이_claim해도_초과_발급이_없다() throws Exception {
		List<User> users = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			users.add(userWithTicket());
		}
		ExecutorService pool = Executors.newFixedThreadPool(32);
		List<String> results = new ArrayList<>();
		try {
			List<Callable<String>> calls = users.stream().<Callable<String>>map(u -> () -> {
				String body = claim(u);
				return (String) JsonPath.read(body, "$.data.result");
			}).toList();
			for (Future<String> f : pool.invokeAll(calls)) {
				results.add(f.get());
			}
		}
		finally {
			pool.shutdown();
		}

		// 종류별 10장 × 10종, 한 사람이 종류마다 1장씩 → 정확히 10명 성공, 140명 품절
		assertThat(results.stream().filter("SUCCESS"::equals).count()).isEqualTo(10);
		assertThat(results.stream().filter("SOLD_OUT"::equals).count()).isEqualTo(140);
		for (long id = 1; id <= 10; id++) {
			assertThat(redis.opsForValue().get(CouponStockStore.stockKey(1, id))).isEqualTo("0");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class)).isEqualTo(150);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM issuance_history WHERE result = 'SUCCESS'", Integer.class)).isEqualTo(10);
	}

}
