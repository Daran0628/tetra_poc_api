package io.tetra.issuance.filter;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.tetra.issuance.common.ApiResponse;
import io.tetra.issuance.common.ErrorResponseWriter;
import io.tetra.issuance.common.PemPublicKeys;
import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.service.EventMeta;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestJwtFactory;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** JwtAuthFilter 단위 테스트 — 컨테이너 없이 MockMvc + 가짜 이벤트 캐시. 시각은 고정 Clock. */
class JwtAuthFilterTest {

	static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");
	static final TetraProperties.Jwt CONFIG = new TetraProperties.Jwt(128, Duration.ofSeconds(5), Duration.ofMinutes(5), null);

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();
	final EventMetaCache events = mock(EventMetaCache.class);
	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		when(events.find(anyLong())).thenReturn(Optional.empty());
		when(events.find(1L)).thenReturn(Optional.of(new EventMeta(1L, "poctenant001",
				LocalDateTime.of(2026, 9, 29, 10, 0), PemPublicKeys.parseRsa(tenant.publicKeyPem()))));
		when(events.find(2L)).thenReturn(Optional.of(new EventMeta(2L, "poctenant001",
				LocalDateTime.of(2026, 9, 29, 10, 0), null))); // 공개키 미등록 이벤트

		var filter = new JwtAuthFilter(events, CONFIG, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")),
				new ErrorResponseWriter(JsonMapper.builder().build()));
		mockMvc = MockMvcBuilders.standaloneSetup(new EchoController())
				.addFilter(filter, JwtAuthFilter.PATH)
				.build();
	}

	/** 기본: 고정 시각 NOW 에 발급, 90초 후 만료 */
	TestJwtFactory.Builder token() {
		return tenant.token().issuedAt(NOW);
	}

	ResultActions enter(String jwt) throws Exception {
		return mockMvc.perform(get(JwtAuthFilter.PATH).param("JWT", jwt));
	}

	ResultActions expectError(ResultActions result, int status, String code) throws Exception {
		return result.andExpect(status().is(status))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value(code));
	}

	// --- 정상 -----------------------------------------------------------------

	@Test
	void 정상_토큰은_통과하고_검증된_값이_요청에_실린다() throws Exception {
		enter(token().userId("user-42").jti("jti-abc").sign())
				.andExpect(status().isOk())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(jsonPath("$.data.userId").value("user-42"))
				.andExpect(jsonPath("$.data.tenantId").value("poctenant001"))
				.andExpect(jsonPath("$.data.eventId").value(1))
				.andExpect(jsonPath("$.data.jti").value("jti-abc"));
	}

	@Test
	void 다른_경로에는_필터가_적용되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor")).andExpect(status().isOk());
	}

	// --- 토큰 형식 ---------------------------------------------------------------

	@Test
	void 토큰이_없으면_400_JWT_MISSING() throws Exception {
		expectError(mockMvc.perform(get(JwtAuthFilter.PATH)), 400, "JWT_MISSING");
	}

	@Test
	void JWT_형식이_아니면_400_JWT_MALFORMED() throws Exception {
		expectError(enter("not-a-jwt"), 400, "JWT_MALFORMED");
	}

	// --- 서명·알고리즘 ------------------------------------------------------------

	@Test
	void 다른_키로_서명하면_401() throws Exception {
		expectError(enter(token().signWithOtherKey()), 401, "JWT_INVALID_SIGNATURE");
	}

	@Test
	void 페이로드를_바꿔치기하면_401() throws Exception {
		expectError(enter(token().tamperedPayload("attacker")), 401, "JWT_INVALID_SIGNATURE");
	}

	@Test
	void alg_none_토큰은_401() throws Exception {
		expectError(enter(token().unsigned()), 401, "JWT_INVALID_SIGNATURE");
	}

	@Test
	void 공개키를_비밀키로_쓴_HS256_토큰은_401() throws Exception {
		expectError(enter(token().hs256WithPublicKeyAsSecret()), 401, "JWT_INVALID_SIGNATURE");
	}

	// --- 만료 -----------------------------------------------------------------

	@Test
	void 만료되면_401_JWT_EXPIRED() throws Exception {
		expectError(enter(token().issuedAt(NOW.minusSeconds(100)).expiresAt(NOW.minusSeconds(10)).sign()),
				401, "JWT_EXPIRED");
	}

	@Test
	void 시계_오차_허용치_5초_안이면_통과() throws Exception {
		enter(token().issuedAt(NOW.minusSeconds(90)).expiresAt(NOW.minusSeconds(3)).sign())
				.andExpect(status().isOk());
	}

	@Test
	void exp가_최대_유효기간_5분보다_멀면_400() throws Exception {
		expectError(enter(token().expiresAt(NOW.plus(Duration.ofMinutes(10))).sign()), 400, "JWT_MALFORMED");
	}

	// --- 클레임 ---------------------------------------------------------------

	@Test
	void jti가_없으면_400() throws Exception {
		expectError(enter(token().jti(null).sign()), 400, "JWT_MALFORMED");
	}

	@Test
	void user_id가_없으면_400() throws Exception {
		expectError(enter(token().userId(null).sign()), 400, "JWT_MALFORMED");
	}

	@Test
	void user_id는_128자까지_허용되고_129자면_400() throws Exception {
		enter(token().userId("u".repeat(128)).sign()).andExpect(status().isOk());
		expectError(enter(token().userId("u".repeat(129)).sign()), 400, "USER_ID_TOO_LONG");
	}

	@Test
	void tenant_id_형식이_틀리면_400() throws Exception {
		expectError(enter(token().tenantId("PocTenant001").sign()), 400, "INVALID_TENANT_ID");
		expectError(enter(token().tenantId("poctenant01").sign()), 400, "INVALID_TENANT_ID");
	}

	@Test
	void 이벤트_소유_테넌트와_다르면_403() throws Exception {
		expectError(enter(token().tenantId("othertenant1").sign()), 403, "TENANT_MISMATCH");
	}

	// --- 이벤트 ---------------------------------------------------------------

	@Test
	void 없는_이벤트면_404() throws Exception {
		expectError(enter(token().eventId(999L).sign()), 404, "EVENT_NOT_FOUND");
	}

	@Test
	void 공개키가_등록되지_않은_이벤트면_404() throws Exception {
		expectError(enter(token().eventId(2L).sign()), 404, "EVENT_NOT_FOUND");
	}

	@Test
	void event_id가_없으면_400() throws Exception {
		expectError(enter(token().eventId(null).sign()), 400, "JWT_MALFORMED");
	}

	/** 필터를 통과하면 검증된 값을 그대로 돌려주는 테스트용 컨트롤러 */
	@RestController
	static class EchoController {

		@GetMapping(JwtAuthFilter.PATH)
		ApiResponse<VerifiedEntryToken> session(HttpServletRequest request) {
			return ApiResponse.ok(VerifiedEntryToken.from(request));
		}

		@GetMapping("/api/issuance/events/1/queue/cursor")
		ApiResponse<String> cursor() {
			return ApiResponse.ok("no token needed");
		}

	}

}
