package io.tetra.issuance.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.SessionKeys;
import io.tetra.issuance.redis.SessionStore;
import jakarta.servlet.http.Cookie;

/**
 * 실제 Redis(Testcontainers) + 실제 필터 등록으로 확인: 세션 해시 형식(D5) 읽기, 필터 적용 경로.
 * 세션은 테스트가 직접 넣는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionAuthFilterIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	SessionStore sessionStore;

	String putSession(Map<String, String> fields) {
		String sid = SessionKeys.newSessionId();
		redis.opsForHash().putAll(SessionKeys.sessionKey(sid), fields);
		redis.expire(SessionKeys.sessionKey(sid), Duration.ofMinutes(5));
		return sid;
	}

	@Test
	void Redis_세션_해시를_필드_형식대로_읽는다() {
		String sid = putSession(Map.of("tenant_id", "poctenant001", "event_id", "1", "user_id", "user-0001",
				"ticket_number", "1234", "queue_entered_at", "1790000000000"));

		var session = sessionStore.find(sid).orElseThrow();
		assertThat(session.tenantId()).isEqualTo("poctenant001");
		assertThat(session.eventId()).isEqualTo(1L);
		assertThat(session.userId()).isEqualTo("user-0001");
		assertThat(session.ticketNumber()).isEqualTo(1234L);
		assertThat(session.queueEnteredAt()).isEqualTo(Instant.ofEpochMilli(1790000000000L));
		assertThat(session.hasTicket()).isTrue();
	}

	@Test
	void 번호표_전_세션과_깨진_세션() {
		String noTicket = putSession(Map.of("tenant_id", "poctenant001", "event_id", "1", "user_id", "user-0002"));
		assertThat(sessionStore.find(noTicket).orElseThrow().hasTicket()).isFalse();

		String broken = putSession(Map.of("tenant_id", "poctenant001", "event_id", "not-a-number", "user_id", "u"));
		assertThat(sessionStore.find(broken)).isEmpty();
		assertThat(sessionStore.find(SessionKeys.newSessionId())).isEmpty();
	}

	@Test
	void 세션이_있으면_필터를_통과한다() throws Exception {
		String sid = putSession(Map.of("tenant_id", "poctenant001", "event_id", "1", "user_id", "user-0003"));
		mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie("TETRA_SID", sid)))
				.andExpect(status().isOk()); // 필터 통과 → 번호표 발급
	}

	@Test
	void 세션이_없으면_필터가_막는다() throws Exception {
		mockMvc.perform(post("/api/issuance/events/1/ticket"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
	}

	@Test
	void 커서_API는_세션_없이_필터를_통과한다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor"))
				.andExpect(status().isOk()) // SESSION_NOT_FOUND(401)이 아님
				.andExpect(jsonPath("$.data.cursor").isNumber());
	}

}
