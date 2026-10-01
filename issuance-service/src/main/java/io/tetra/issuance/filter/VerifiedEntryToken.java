package io.tetra.issuance.filter;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * JwtAuthFilter 가 서명·만료·형식·테넌트 검증을 모두 마친 입장 토큰 값.
 * 세션에 넣는 값은 반드시 여기서 꺼낸다 (서명 검증 전 값은 키 선택에만 쓰였음).
 */
public record VerifiedEntryToken(String userId, String tenantId, long eventId, String jti, Instant expiresAt) {

	static final String ATTRIBUTE = VerifiedEntryToken.class.getName();

	/** 요청에서 꺼낸다. 세션 API(JwtAuthFilter 적용 경로) 밖에서 부르면 null. */
	public static VerifiedEntryToken from(HttpServletRequest request) {
		return (VerifiedEntryToken) request.getAttribute(ATTRIBUTE);
	}

}
