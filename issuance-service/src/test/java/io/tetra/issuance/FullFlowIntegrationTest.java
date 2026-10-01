package io.tetra.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.tetra.issuance.config.TetraProperties.WarmupMode;
import io.tetra.issuance.redis.CouponStockStore;
import io.tetra.issuance.redis.CursorStore;
import io.tetra.issuance.redis.TicketStore;
import io.tetra.issuance.service.CouponCatalog;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestJwtFactory;
import jakarta.servlet.http.Cookie;

/**
 * M7 전체 흐름: 테넌트 JWT → 세션(302+쿠키) → 번호표 → 커서 폴링(내 번호 도달까지) → 쿠폰 목록 → claim → 발급 이력.
 * 클라이언트는 폴링 스펙대로 "받은 커서 최댓값 ≥ 내 번호"가 되면 claim 한다.
 * 3초 윈도우를 실제로 기다리지 않고 락 키를 지워 윈도우가 지난 상태를 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FullFlowIntegrationTest {

	static final String BASE = "/api/issuance/events/1";
	static final Pattern SID = Pattern.compile("TETRA_SID=([A-Za-z0-9_-]{43});");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	@Autowired
	CouponCatalog catalog;

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();

	@BeforeEach
	void 이벤트를_처음_상태로() {
		jdbcTemplate.update("UPDATE event SET public_key = ? WHERE event_id = 1", tenant.publicKeyPem());
		eventMetaCache.reload();
		catalog.warmup(WarmupMode.FORCE);
		jdbcTemplate.update("DELETE FROM issuance_history");
		redis.delete(List.of(CursorStore.cursorKey(1), CursorStore.lockKey(1), TicketStore.seqKey(1),
				TicketStore.totalKey(1), TicketStore.retryKey(1)));
	}

	/** 클라이언트 한 명의 상태 */
	static final class Client {
		final String userId;
		String sid;
		long ticketNumber;
		long maxCursor;
		String claimResult;
		int couponCount;

		Client(String userId) {
			this.userId = userId;
		}
	}

	// --- 단계별 동작 (프론트가 하는 일) ------------------------------------------------

	void enter(Client c) throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/api/issuance/session")
				.param("JWT", tenant.token().userId(c.userId).sign())).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(302);
		assertThat(res.getHeader("Location")).isEqualTo("/");
		Matcher m = SID.matcher(res.getHeader("Set-Cookie"));
		assertThat(m.find()).isTrue();
		c.sid = m.group(1);
	}

	void takeTicket(Client c) throws Exception {
		String body = mockMvc.perform(post(BASE + "/ticket").cookie(cookie(c)))
				.andReturn().getResponse().getContentAsString();
		c.ticketNumber = ((Number) JsonPath.read(body, "$.data.ticketNumber")).longValue();
	}

	/** 커서 1회 폴링 — 쿠키 없이 호출, 받은 값의 최댓값만 유지 (폴링 스펙 maxCursorRef) */
	long pollCursor(Client c) throws Exception {
		String body = mockMvc.perform(get(BASE + "/queue/cursor")).andReturn().getResponse().getContentAsString();
		long cursor = ((Number) JsonPath.read(body, "$.data.cursor")).longValue();
		c.maxCursor = Math.max(c.maxCursor, cursor);
		return c.maxCursor;
	}

	/** 내 차례(gap ≤ 0)가 될 때까지 폴링. 한 번 폴링할 때마다 3초가 지난 것으로 친다. */
	void waitForTurn(Client c) throws Exception {
		for (int i = 0; i < 10_000 && c.ticketNumber - pollCursor(c) > 0; i++) {
			redis.delete(CursorStore.lockKey(1));
		}
		assertThat(c.ticketNumber - c.maxCursor).isLessThanOrEqualTo(0);
	}

	void claim(Client c) throws Exception {
		String body = mockMvc.perform(post(BASE + "/coupons/claim").cookie(cookie(c)))
				.andReturn().getResponse().getContentAsString();
		c.claimResult = JsonPath.read(body, "$.data.result");
		c.couponCount = ((List<?>) JsonPath.read(body, "$.data.coupons")).size();
	}

	static Cookie cookie(Client c) {
		return new Cookie("TETRA_SID", c.sid);
	}

	// --- 시나리오 ---------------------------------------------------------------

	@Test
	void 한_사용자가_입장부터_쿠폰_발급까지_끝까지_간다() throws Exception {
		Client c = new Client("flow-" + UUID.randomUUID());

		enter(c);
		takeTicket(c);
		waitForTurn(c);

		String coupons = mockMvc.perform(get(BASE + "/coupons").cookie(cookie(c)))
				.andReturn().getResponse().getContentAsString();
		assertThat((List<?>) JsonPath.read(coupons, "$.data.coupons")).hasSize(10);

		claim(c);
		assertThat(c.claimResult).isEqualTo("SUCCESS");
		assertThat(c.couponCount).isEqualTo(10);

		var row = jdbcTemplate.queryForMap(
				"SELECT result, ticket_number, served_at FROM issuance_history WHERE user_id = ?", c.userId);
		assertThat(row.get("result")).isEqualTo("SUCCESS");
		assertThat(((Number) row.get("ticket_number")).longValue()).isEqualTo(c.ticketNumber);
		assertThat(row.get("served_at")).isNull();
	}

	@Test
	void 사용자_200명이면_번호표_앞_10명이_100장을_받고_나머지는_품절() throws Exception {
		List<Client> clients = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			Client c = new Client("crowd-" + i + "-" + UUID.randomUUID());
			enter(c);
			takeTicket(c);
			clients.add(c);
		}
		// 번호가 빠른 사람부터 차례가 오고, 차례가 온 사람이 claim 한다
		clients.sort(Comparator.comparingLong(c -> c.ticketNumber));
		for (Client c : clients) {
			waitForTurn(c);
			claim(c);
		}

		List<Client> winners = clients.stream().filter(c -> "SUCCESS".equals(c.claimResult)).toList();
		assertThat(winners).hasSize(10);
		assertThat(winners).allMatch(c -> c.couponCount == 10);
		assertThat(winners).containsExactlyElementsOf(clients.subList(0, 10)); // 번호표 순서 = 발급 순서
		assertThat(clients.subList(10, 200)).allMatch(c -> "SOLD_OUT".equals(c.claimResult) && c.couponCount == 0);

		// 재고 정합성: Redis 남은 재고 + 발급 장수 = 100
		long remaining = 0;
		for (long id = 1; id <= 10; id++) {
			remaining += Long.parseLong(redis.opsForValue().get(CouponStockStore.stockKey(1, id)));
		}
		long issued = winners.stream().mapToLong(c -> c.couponCount).sum();
		assertThat(remaining).isZero();
		assertThat(remaining + issued).isEqualTo(100);

		// 발급 이력: 200행, 성공 10 / 품절 190
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class)).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM issuance_history WHERE result = 'SUCCESS'", Integer.class)).isEqualTo(10);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM issuance_history WHERE result = 'FAILED_SOLDOUT'", Integer.class)).isEqualTo(190);
	}

}
