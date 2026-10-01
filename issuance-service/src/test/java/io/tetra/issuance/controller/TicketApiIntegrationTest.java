package io.tetra.issuance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import io.tetra.issuance.TestcontainersConfiguration;
import io.tetra.issuance.redis.SessionKeys;
import io.tetra.issuance.redis.SessionStore;
import io.tetra.issuance.redis.TicketStore;
import jakarta.servlet.http.Cookie;

/** 4-2 번호표 API — 실제 Redis·Lua. 다른 테스트와 Redis 를 공유하므로 카운터는 "증가량"으로 확인한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TicketApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	SessionStore sessionStore;

	@Autowired
	TicketStore ticketStore;

	String newSession() {
		return sessionStore.create("poctenant001", 1L, "user-" + UUID.randomUUID(), Duration.ofMinutes(10));
	}

	MvcResult issue(String sid) throws Exception {
		return mockMvc.perform(post("/api/issuance/events/1/ticket").cookie(new Cookie("TETRA_SID", sid))).andReturn();
	}

	static long ticketNumberOf(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.ticketNumber")).longValue();
	}

	long counter(String key) {
		String v = redis.opsForValue().get(key);
		return v == null ? 0 : Long.parseLong(v);
	}

	@Test
	void 번호표를_발급하고_세션에_번호와_발급_시각을_기록한다() throws Exception {
		String sid = newSession();
		long before = Instant.now().toEpochMilli();

		long number = ticketNumberOf(issue(sid));

		Map<Object, Object> hash = redis.opsForHash().entries(SessionKeys.sessionKey(sid));
		assertThat(hash.get("ticket_number")).isEqualTo(Long.toString(number));
		long enteredAt = Long.parseLong(hash.get("queue_entered_at").toString());
		assertThat(enteredAt).isBetween(before - 1000, Instant.now().toEpochMilli() + 1000);
		assertThat(counter(TicketStore.seqKey(1))).isGreaterThanOrEqualTo(number);
	}

	@Test
	void 재발급하면_새_번호로_덮어쓰고_retry가_오른다() throws Exception {
		String sid = newSession();
		long total0 = counter(TicketStore.totalKey(1));
		long retry0 = counter(TicketStore.retryKey(1));

		long first = ticketNumberOf(issue(sid));
		assertThat(counter(TicketStore.totalKey(1))).isEqualTo(total0 + 1);
		assertThat(counter(TicketStore.retryKey(1))).isEqualTo(retry0);

		long second = ticketNumberOf(issue(sid));
		assertThat(second).isGreaterThan(first);
		assertThat(redis.opsForHash().get(SessionKeys.sessionKey(sid), "ticket_number")).isEqualTo(Long.toString(second));
		assertThat(counter(TicketStore.totalKey(1))).isEqualTo(total0 + 1); // 신규 아님
		assertThat(counter(TicketStore.retryKey(1))).isEqualTo(retry0 + 1); // 버려진 번호 1개
	}

	@Test
	void 세션이_사라졌으면_Lua가_발급하지_않는다() {
		long seq0 = counter(TicketStore.seqKey(1));
		assertThat(ticketStore.issue(SessionKeys.newSessionId(), 1L, Instant.now())).isEmpty();
		assertThat(counter(TicketStore.seqKey(1))).isEqualTo(seq0); // 번호를 소모하지 않음
	}

	@Test
	void 동시에_200명이_요청해도_번호가_겹치지_않는다() throws Exception {
		List<String> sessions = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			sessions.add(newSession());
		}
		ExecutorService pool = Executors.newFixedThreadPool(32);
		try {
			List<Callable<Long>> calls = new ArrayList<>();
			for (String sid : sessions) {
				calls.add(() -> ticketNumberOf(issue(sid)));
			}
			List<Long> numbers = new ArrayList<>();
			for (Future<Long> f : pool.invokeAll(calls)) {
				numbers.add(f.get());
			}
			assertThat(numbers).doesNotHaveDuplicates().hasSize(200);
			Collections.sort(numbers);
			// 이 테스트 동안 다른 테스트가 끼어들지 않으면 연속 번호 (JUnit 은 기본으로 순차 실행)
			assertThat(numbers.get(199) - numbers.get(0)).isEqualTo(199);
		}
		finally {
			pool.shutdown();
		}
	}

}
