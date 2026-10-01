package io.tetra.issuance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.tetra.issuance.config.TetraProperties;
import io.tetra.issuance.redis.CursorStore;

/** 시작 전에는 커서를 올리지 않는다(D10) — 고정 Clock 으로 확인. */
class QueueCursorServiceTest {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final LocalDateTime START = LocalDateTime.of(2026, 9, 29, 10, 0);

	final EventMetaCache events = mock(EventMetaCache.class);
	final CursorStore cursorStore = mock(CursorStore.class);
	final TetraProperties properties = mock(TetraProperties.class);

	QueueCursorService serviceAt(LocalDateTime now) {
		when(events.find(1L)).thenReturn(Optional.of(new EventMeta(1L, "poctenant001", START, null)));
		when(properties.queue()).thenReturn(new TetraProperties.Queue(300, Duration.ofSeconds(3), Duration.ofSeconds(1)));
		when(cursorStore.advanceAndGet(eq(1L), anyBoolean(), eq(Duration.ofSeconds(3)), anyInt())).thenReturn(0L);
		return new QueueCursorService(events, cursorStore, properties, Clock.fixed(now.atZone(KST).toInstant(), KST));
	}

	@Test
	void 시작_전에는_증가하지_않도록_started_false로_부른다() {
		assertThat(serviceAt(START.minusMinutes(1)).current(1L)).isZero();
		verify(cursorStore).advanceAndGet(1L, false, Duration.ofSeconds(3), 300);
	}

	@Test
	void 시작_정각부터_증가하도록_started_true로_부른다() {
		serviceAt(START).current(1L);
		verify(cursorStore).advanceAndGet(1L, true, Duration.ofSeconds(3), 300);
	}

}
