package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.CursorStore;

/** 4-3 커서 API — 실제 Redis·Lua. 시드 이벤트 1은 이미 시작된 상태라 증가가 일어난다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CursorApiIntegrationTest {

	static final String URL = "/api/issuance/events/1/queue/cursor";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	CursorStore cursorStore;

	@BeforeEach
	void resetCursor() {
		redis.delete(List.of(CursorStore.cursorKey(1), CursorStore.lockKey(1)));
	}

	long fetchCursor() throws Exception {
		String body = mockMvc.perform(get(URL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.data.cursor")).longValue();
	}

	@Test
	void 쿠키_없이_200이고_CDN용_캐시_헤더를_준다() throws Exception {
		mockMvc.perform(get(URL))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "public, max-age=0, s-maxage=1"))
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.cursor").value(300)); // 윈도우 첫 요청이 +300
	}

	@Test
	void 같은_윈도우_안에서는_다시_오르지_않는다() throws Exception {
		assertThat(fetchCursor()).isEqualTo(300);
		assertThat(fetchCursor()).isEqualTo(300);
		assertThat(fetchCursor()).isEqualTo(300);
	}

	@Test
	void 락_TTL은_윈도우_3초() throws Exception {
		fetchCursor();
		assertThat(redis.getExpire(CursorStore.lockKey(1), TimeUnit.MILLISECONDS)).isBetween(2000L, 3000L);
	}

	@Test
	void 윈도우가_지나면_다시_300_오른다() throws Exception {
		assertThat(fetchCursor()).isEqualTo(300);
		redis.delete(CursorStore.lockKey(1)); // 3초 경과(락 만료)와 같은 상태
		assertThat(fetchCursor()).isEqualTo(600);
		redis.delete(CursorStore.lockKey(1));
		assertThat(fetchCursor()).isEqualTo(900);
	}

	@Test
	void Lua를_동시에_1000번_불러도_한_윈도우에_정확히_1번만_오른다() throws Exception {
		// HTTP 처리 시간 때문에 3초 윈도우가 지나지 않도록 Lua 를 직접, 윈도우 60초로 호출 (원자성 확인용)
		ExecutorService pool = Executors.newFixedThreadPool(64);
		try {
			List<Callable<Long>> calls = new ArrayList<>();
			for (int i = 0; i < 1000; i++) {
				calls.add(() -> cursorStore.advanceAndGet(1L, true, Duration.ofSeconds(60), 300));
			}
			List<Long> results = new ArrayList<>();
			for (Future<Long> f : pool.invokeAll(calls)) {
				results.add(f.get());
			}
			assertThat(results).hasSize(1000).containsOnly(300L);
			assertThat(redis.opsForValue().get(CursorStore.cursorKey(1))).isEqualTo("300");
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void HTTP_동시_1000건_동안_커서는_3초마다_최대_1번만_오른다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(64);
		try {
			List<Callable<Long>> calls = new ArrayList<>();
			for (int i = 0; i < 1000; i++) {
				calls.add(this::fetchCursor);
			}
			long startedAt = System.nanoTime();
			List<Long> results = new ArrayList<>();
			for (Future<Long> f : pool.invokeAll(calls)) {
				results.add(f.get());
			}
			long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

			long finalCursor = Long.parseLong(redis.opsForValue().get(CursorStore.cursorKey(1)));
			long increments = finalCursor / 300;
			assertThat(finalCursor % 300).isZero();
			assertThat(results).allMatch(c -> c % 300 == 0 && c >= 300 && c <= finalCursor);
			// 지난 윈도우 수(+시작 윈도우 1개)보다 많이 오르면 안 된다
			assertThat(increments).isBetween(1L, elapsedMs / 3000 + 1);
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void 없는_이벤트는_404이고_캐시되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/999/queue/cursor"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
		assertThat(redis.hasKey(CursorStore.cursorKey(999))).isFalse();
	}

	@Test
	void eventId가_숫자가_아니면_400이고_캐시되지_않는다() throws Exception {
		mockMvc.perform(get("/api/issuance/events/abc/queue/cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

}
