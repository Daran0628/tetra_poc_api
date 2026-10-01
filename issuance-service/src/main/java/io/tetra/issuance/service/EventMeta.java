package io.tetra.issuance.service;

import java.security.interfaces.RSAPublicKey;
import java.time.LocalDateTime;

/**
 * 요청 처리에 필요한 이벤트 정보 (메모리 캐시용).
 *
 * @param publicKey JWT 검증 공개키. event.public_key 가 비었거나 읽을 수 없으면 null — 이 이벤트로는 입장 불가
 */
public record EventMeta(long eventId, String tenantId, LocalDateTime startAt, RSAPublicKey publicKey) {

	public boolean hasPublicKey() {
		return publicKey != null;
	}

}
