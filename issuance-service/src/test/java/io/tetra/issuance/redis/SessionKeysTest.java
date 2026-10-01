package io.tetra.issuance.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SessionKeysTest {

	@Test
	void 새_세션_ID는_43자_URL_safe이고_매번_다르다() {
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String sid = SessionKeys.newSessionId();
			assertThat(sid).hasSize(43).matches("[A-Za-z0-9_-]+");
			assertThat(SessionKeys.isValidSessionId(sid)).isTrue();
			ids.add(sid);
		}
		assertThat(ids).hasSize(1000);
	}

	@Test
	void 형식이_틀린_세션_ID는_거절한다() {
		assertThat(SessionKeys.isValidSessionId(null)).isFalse();
		assertThat(SessionKeys.isValidSessionId("short")).isFalse();
		assertThat(SessionKeys.isValidSessionId("a".repeat(42) + "*")).isFalse();
		assertThat(SessionKeys.isValidSessionId("a".repeat(44))).isFalse();
	}

	@Test
	void 키_이름() {
		assertThat(SessionKeys.sessionKey("abc")).isEqualTo("session:abc");
		assertThat(SessionKeys.sessionIndexKey(1L, "user-0001")).isEqualTo("session:idx:1:user-0001");
	}

}
