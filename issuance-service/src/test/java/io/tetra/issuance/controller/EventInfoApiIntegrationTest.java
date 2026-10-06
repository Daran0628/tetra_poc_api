package io.tetra.issuance.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.service.EventMetaCache;

/** 4-0 이벤트 정보 API — 실제 DB 시드 기준. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EventInfoApiIntegrationTest {

	static final String URL = "/api/issuance/events/1/info";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EventMetaCache eventMetaCache;

	@AfterEach
	void 시드_값으로_되돌리기() {
		jdbcTemplate.update("UPDATE event SET banner_image_path = '', endpoint_url = '' WHERE event_id = 1");
		eventMetaCache.reload();
	}

	@Test
	void 쿠키_없이_200이고_시드_이벤트_정보를_KST_ISO_시각으로_준다() throws Exception {
		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "public, max-age=0, s-maxage=60"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.eventId").value(1))
				.andExpect(jsonPath("$.data.name").value("PoC 쿠폰 발급 이벤트"))
				.andExpect(jsonPath("$.data.startAt").value("2026-09-29T10:00:00+09:00"))
				.andExpect(jsonPath("$.data.endAt").value("2026-10-29T10:00:00+09:00"))
				.andExpect(jsonPath("$.data.bannerUrl").value("")) // 시드는 빈 문자열
				.andExpect(jsonPath("$.data.returnUrl").value(""));
	}

	@Test
	void 배너와_복귀_주소가_있으면_그대로_준다() throws Exception {
		jdbcTemplate.update("UPDATE event SET banner_image_path = ?, endpoint_url = ? WHERE event_id = 1",
				"/assets/banner.png", "https://shop.example.com/event");
		eventMetaCache.reload();

		mockMvc.perform(get(URL))
				.andExpect(jsonPath("$.data.bannerUrl").value("/assets/banner.png"))
				.andExpect(jsonPath("$.data.returnUrl").value("https://shop.example.com/event"));
	}

	@Test
	void 응답에_내부_정보는_없다() throws Exception {
		mockMvc.perform(get(URL))
				.andExpect(jsonPath("$.data.tenantId").doesNotExist())
				.andExpect(jsonPath("$.data.publicKey").doesNotExist());
	}

	@Test
	void 없는_이벤트는_404이고_캐시되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/999/info"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
	}

	@Test
	void eventId가_숫자가_아니면_400() throws Exception {
		mockMvc.perform(get("/api/issuance/events/abc/info"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

}
