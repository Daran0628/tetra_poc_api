package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.SessionKeys;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestJwtFactory;
import jakarta.servlet.http.Cookie;

/**
 * 4-1 세션 API — 실제 Redis·실제 필터로 확인. 테스트마다 다른 user_id 를 써서 서로 영향이 없게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiIntegrationTest {

	static final Pattern SID_IN_COOKIE = Pattern.compile("TETRA_SID=([A-Za-z0-9_-]{43});");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();
	String userId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("UPDATE event SET public_key = ? WHERE event_id = 1", tenant.publicKeyPem());
		eventMetaCache.reload();
		userId = "user-" + UUID.randomUUID();
	}

	MvcResult enter(String jwt) throws Exception {
		return mockMvc.perform(get("/api/issuance/session").param("JWT", jwt)).andReturn();
	}

	static String sidOf(MvcResult result) {
		Matcher m = SID_IN_COOKIE.matcher(result.getResponse().getHeader("Set-Cookie"));
		assertThat(m.find()).as("Set-Cookie 에 세션 ID").isTrue();
		return m.group(1);
	}

	long entryCount() {
		String v = redis.opsForValue().get("entry:count:1");
		return v == null ? 0 : Long.parseLong(v);
	}

	// --- 정상 발급 -------------------------------------------------------------

	@Test
	void 정상_토큰이면_302_상대경로와_세션_쿠키를_준다() throws Exception {
		MvcResult result = enter(tenant.token().userId(userId).sign());

		var res = result.getResponse();
		assertThat(res.getStatus()).isEqualTo(302);
		assertThat(res.getHeader("Location")).isEqualTo("/?event=1"); // 숫자 event_id 를 쿼리로 (도메인 첫 라벨은 해시값)
		assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(res.getHeader("Referrer-Policy")).isEqualTo("no-referrer");

		String setCookie = res.getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("TETRA_SID=")
				.contains("Path=/", "Max-Age=7200", "Secure", "HttpOnly", "SameSite=Lax")
				.doesNotContainIgnoringCase("Domain=");
	}

	@Test
	void Redis에_세션_해시와_역참조_키가_TTL과_함께_생긴다() throws Exception {
		String sid = sidOf(enter(tenant.token().userId(userId).sign()));

		Map<Object, Object> hash = redis.opsForHash().entries(SessionKeys.sessionKey(sid));
		assertThat(hash).containsEntry("tenant_id", "poctenant001")
				.containsEntry("event_id", "1")
				.containsEntry("user_id", userId)
				.doesNotContainKeys("ticket_number", "queue_entered_at"); // 번호표는 M4
		assertThat(redis.getExpire(SessionKeys.sessionKey(sid), TimeUnit.SECONDS)).isBetween(7100L, 7200L);
		assertThat(redis.opsForValue().get(SessionKeys.sessionIndexKey(1L, userId))).isEqualTo(sid);
	}

	@Test
	void 발급된_세션_쿠키로_이벤트_API_필터를_통과한다() throws Exception {
		String sid = sidOf(enter(tenant.token().userId(userId).sign()));
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie("TETRA_SID", sid)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ticketNumber").isNumber());
	}

	// --- jti 1회 사용 ------------------------------------------------------------

	@Test
	void 같은_토큰을_두_번_쓰면_두_번째는_401_JWT_REUSED() throws Exception {
		String jwt = tenant.token().userId(userId).sign();
		assertThat(enter(jwt).getResponse().getStatus()).isEqualTo(302);

		mockMvc.perform(get("/api/issuance/session").param("JWT", jwt))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("JWT_REUSED"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void jti_키는_토큰_만료_무렵까지만_남는다() throws Exception {
		String jti = "jti-" + UUID.randomUUID();
		enter(tenant.token().userId(userId).jti(jti).sign()); // 90초 후 만료 + 시계 오차 5초
		assertThat(redis.getExpire("jti:" + jti, TimeUnit.SECONDS)).isBetween(80L, 95L);
	}

	// --- 세션 재사용 ------------------------------------------------------------

	@Test
	void 같은_사용자가_새_토큰으로_다시_들어오면_세션을_재사용하고_진입_카운터는_그대로() throws Exception {
		long before = entryCount();
		String first = sidOf(enter(tenant.token().userId(userId).sign()));
		assertThat(entryCount()).isEqualTo(before + 1);

		String second = sidOf(enter(tenant.token().userId(userId).sign()));
		assertThat(second).isEqualTo(first);
		assertThat(entryCount()).isEqualTo(before + 1);
	}

	@Test
	void 다른_사용자는_다른_세션을_받고_진입_카운터가_오른다() throws Exception {
		long before = entryCount();
		String a = sidOf(enter(tenant.token().userId(userId).sign()));
		String b = sidOf(enter(tenant.token().userId(userId + "-other").sign()));
		assertThat(a).isNotEqualTo(b);
		assertThat(entryCount()).isEqualTo(before + 2);
	}

	@Test
	void 기존_세션이_만료됐으면_새_세션을_만든다() throws Exception {
		String first = sidOf(enter(tenant.token().userId(userId).sign()));
		redis.delete(SessionKeys.sessionKey(first)); // 세션만 만료된 상황 (역참조 키는 남음)

		String second = sidOf(enter(tenant.token().userId(userId).sign()));
		assertThat(second).isNotEqualTo(first);
	}

	// --- 오리진 Host ------------------------------------------------------------

	@Test
	void Host가_오리진_주소로_들어와도_Location은_상대경로_쿠키에_Domain_없음() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/issuance/session")
				.param("JWT", tenant.token().userId(userId).sign())
				.header("Host", "origin.gamza-dev.shop")
				.with(r -> { r.setServerName("origin.gamza-dev.shop"); r.setScheme("http"); r.setSecure(false); return r; }))
				.andReturn();

		assertThat(result.getResponse().getHeader("Location")).isEqualTo("/?event=1");
		assertThat(result.getResponse().getHeader("Set-Cookie"))
				.contains("Secure") // 요청이 http 여도 설정값으로 붙음
				.doesNotContainIgnoringCase("Domain=")
				.doesNotContain("origin.gamza-dev.shop");
	}

}
