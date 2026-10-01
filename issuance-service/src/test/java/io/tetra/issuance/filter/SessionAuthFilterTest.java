package io.tetra.issuance.filter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.tetra.issuance.common.ApiResponse;
import io.tetra.issuance.common.ErrorResponseWriter;
import io.tetra.issuance.redis.IssuanceSession;
import io.tetra.issuance.redis.SessionKeys;
import io.tetra.issuance.redis.SessionStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** SessionAuthFilter 단위 테스트 — 컨테이너 없이 MockMvc + 가짜 세션 저장소. */
class SessionAuthFilterTest {

	static final String COOKIE = "TETRA_SID";
	static final String SID = SessionKeys.newSessionId();
	static final String SID_NO_TICKET = SessionKeys.newSessionId();

	final SessionStore store = mock(SessionStore.class);
	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		when(store.find(anyString())).thenReturn(Optional.empty());
		when(store.find(SID)).thenReturn(Optional.of(new IssuanceSession(SID, "poctenant001", 1L, "user-0001", 1234L,
				Instant.parse("2026-10-01T06:00:00Z"))));
		when(store.find(SID_NO_TICKET)).thenReturn(
				Optional.of(new IssuanceSession(SID_NO_TICKET, "poctenant001", 1L, "user-0002", null, null)));

		var filter = new SessionAuthFilter(store, COOKIE, new ErrorResponseWriter(JsonMapper.builder().build()));
		mockMvc = MockMvcBuilders.standaloneSetup(new EchoController())
				.addFilter(filter, SessionAuthFilter.URL_PATTERN)
				.build();
	}

	ResultActions expectError(ResultActions result, int status, String code) throws Exception {
		return result.andExpect(status().is(status))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value(code));
	}

	// --- 통과 -----------------------------------------------------------------

	@Test
	void 유효한_세션이면_통과하고_세션_내용이_요청에_실린다() throws Exception {
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie(COOKIE, SID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.userId").value("user-0001"))
				.andExpect(jsonPath("$.data.tenantId").value("poctenant001"))
				.andExpect(jsonPath("$.data.eventId").value(1))
				.andExpect(jsonPath("$.data.ticketNumber").value(1234));
	}

	@Test
	void 번호표_발급_전_세션도_통과한다() throws Exception {
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie(COOKIE, SID_NO_TICKET)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ticketNumber").doesNotExist());
	}

	@Test
	void 쿠폰_목록과_claim_경로도_세션이_필요하다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/coupons").cookie(new Cookie(COOKIE, SID))).andExpect(status().isOk());
		expectError(mockMvc.perform(get("/api/issuance/events/1/coupons")), 401, "SESSION_NOT_FOUND");
		expectError(mockMvc.perform(post("/api/issuance/events/1/coupons/claim")), 401, "SESSION_NOT_FOUND");
	}

	@Test
	void 커서_API는_쿠키_없이_통과하고_세션을_조회하지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor")).andExpect(status().isOk());
		verify(store, never()).find(anyString());
	}

	// --- 거절 -----------------------------------------------------------------

	@Test
	void 쿠키가_없으면_401() throws Exception {
		expectError(mockMvc.perform(post("/api/issuance/events/1/ticket")), 401, "SESSION_NOT_FOUND");
	}

	@Test
	void 쿠키_형식이_틀리면_Redis를_조회하지_않고_401() throws Exception {
		expectError(mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie(COOKIE, "short"))),
				401, "SESSION_NOT_FOUND");
		verify(store, never()).find(anyString());
	}

	@Test
	void 세션이_없거나_만료되면_401() throws Exception {
		expectError(mockMvc.perform(post("/api/issuance/events/1/ticket")
				.cookie(new Cookie(COOKIE, SessionKeys.newSessionId()))), 401, "SESSION_NOT_FOUND");
	}

	@Test
	void 다른_이벤트의_세션이면_403() throws Exception {
		expectError(mockMvc.perform(post("/api/issuance/events/2/ticket").cookie(new Cookie(COOKIE, SID))),
				403, "SESSION_EVENT_MISMATCH");
	}

	@Test
	void eventId가_숫자가_아니면_400() throws Exception {
		expectError(mockMvc.perform(post("/api/issuance/events/abc/ticket").cookie(new Cookie(COOKIE, SID))),
				400, "INVALID_REQUEST");
	}

	@Test
	void 커서_경로로_위장한_상대경로_조작은_통과시키지_않는다() throws Exception {
		mockMvc.perform(post("/api/issuance/events/1/queue/cursor/../../ticket"))
				.andExpect(status().is4xxClientError());
		verify(store, never()).find(anyString());
	}

	@RestController
	static class EchoController {

		@PostMapping("/api/issuance/events/{eventId}/ticket")
		ApiResponse<IssuanceSession> ticket(@PathVariable long eventId, HttpServletRequest request) {
			return ApiResponse.ok(SessionAuthFilter.sessionOf(request));
		}

		@GetMapping("/api/issuance/events/{eventId}/coupons")
		ApiResponse<IssuanceSession> coupons(@PathVariable long eventId, HttpServletRequest request) {
			return ApiResponse.ok(SessionAuthFilter.sessionOf(request));
		}

		@PostMapping("/api/issuance/events/{eventId}/coupons/claim")
		ApiResponse<IssuanceSession> claim(@PathVariable long eventId, HttpServletRequest request) {
			return ApiResponse.ok(SessionAuthFilter.sessionOf(request));
		}

		@GetMapping("/api/issuance/events/{eventId}/queue/cursor")
		ApiResponse<String> cursor(@PathVariable long eventId) {
			return ApiResponse.ok("no session needed");
		}

	}

}
