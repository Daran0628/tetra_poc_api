package io.tetra.issuance.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.service.EventMetaCache;
import io.tetra.issuance.support.TestJwtFactory;

/**
 * 실제 DB(Testcontainers) + 실제 필터 등록(FilterConfig)으로 확인:
 * Event 엔티티가 DB 정의서 스키마와 맞는지(ddl-auto=validate), 이벤트 메타 캐시 로딩, 필터 적용 경로.
 * 세션 API 자체 동작은 SessionApiIntegrationTest 에서 확인.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthFilterIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	final TestJwtFactory tenant = TestJwtFactory.withNewKeyPair();

	@BeforeEach
	void 테스트_공개키를_시드_이벤트에_등록() {
		jdbcTemplate.update("UPDATE event SET public_key = ? WHERE event_id = 1", tenant.publicKeyPem());
		eventMetaCache.reload();
	}

	@Test
	void 이벤트_메타가_DB에서_로딩된다() {
		var meta = eventMetaCache.find(1L).orElseThrow();
		assertThat(meta.tenantId()).isEqualTo("poctenant001");
		assertThat(meta.startAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 10, 0));
		assertThat(meta.hasPublicKey()).isTrue();
		assertThat(eventMetaCache.find(999L)).isEmpty();
	}

	@Test
	void 정상_토큰은_필터를_통과한다() throws Exception {
		mockMvc.perform(get("/api/issuance/session").param("JWT", tenant.token().userId("filter-it-user").sign()))
				.andExpect(status().isFound()) // 필터 통과 → 세션 컨트롤러가 302 (M3)
				.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}

	@Test
	void 잘못된_토큰은_필터가_막는다() throws Exception {
		mockMvc.perform(get("/api/issuance/session").param("JWT", tenant.token().signWithOtherKey()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("JWT_INVALID_SIGNATURE"));
	}

	@Test
	void 세션_API가_아닌_경로에는_필터가_붙지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/1/queue/cursor"))
				.andExpect(status().isOk()) // JWT_MISSING(400)이 아님
				.andExpect(header().doesNotExist("Referrer-Policy"));
	}

}
