package io.tetra.issuance.service;

import java.security.interfaces.RSAPublicKey;
import java.time.LocalDateTime;

/**
 * 요청 처리에 필요한 이벤트 정보 (메모리 캐시용). 시각은 DB 저장 그대로 KST.
 *
 * @param bannerUrl event.banner_image_path — 빈 문자열일 수 있음
 * @param returnUrl event.endpoint_url(테넌트 복귀 주소) — 빈 문자열일 수 있음
 * @param publicKey JWT 검증 공개키. event.public_key 가 비었거나 읽을 수 없으면 null — 이 이벤트로는 입장 불가
 */
public record EventMeta(long eventId, String tenantId, String name, LocalDateTime startAt, LocalDateTime endAt,
		String bannerUrl, String returnUrl, RSAPublicKey publicKey) {

	/** 테스트 등에서 화면용 정보가 필요 없을 때 */
	public EventMeta(long eventId, String tenantId, LocalDateTime startAt, LocalDateTime endAt, RSAPublicKey publicKey) {
		this(eventId, tenantId, "", startAt, endAt, "", "", publicKey);
	}

	public boolean hasPublicKey() {
		return publicKey != null;
	}

	/** start_at 정각부터 시작 */
	public boolean hasStarted(LocalDateTime now) {
		return !now.isBefore(startAt);
	}

	/** end_at 정각부터 종료 (B1) */
	public boolean hasEnded(LocalDateTime now) {
		return !now.isBefore(endAt);
	}

}
