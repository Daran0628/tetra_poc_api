package io.tetra.issuance.common;

import org.springframework.http.HttpStatus;

/**
 * 모든 에러 응답의 코드·HTTP 상태·기본 메시지.
 * 프론트는 {@code error.code} 문자열로 분기하고, HTTP 상태는 {@code res.ok} 판단에 쓴다.
 */
public enum ErrorCode {

	// --- 공통 ---------------------------------------------------------------
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다."),

	// --- JWT (세션 API, 플랜 4-1) ------------------------------------------
	JWT_MISSING(HttpStatus.BAD_REQUEST, "입장 토큰이 없습니다."),
	JWT_MALFORMED(HttpStatus.BAD_REQUEST, "입장 토큰 형식이 올바르지 않습니다."),
	JWT_INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "입장 토큰 서명이 올바르지 않습니다."),
	JWT_EXPIRED(HttpStatus.UNAUTHORIZED, "입장 토큰이 만료되었습니다."),
	JWT_REUSED(HttpStatus.UNAUTHORIZED, "이미 사용된 입장 토큰입니다."),
	INVALID_TENANT_ID(HttpStatus.BAD_REQUEST, "테넌트 ID 형식이 올바르지 않습니다."),
	USER_ID_TOO_LONG(HttpStatus.BAD_REQUEST, "사용자 ID가 너무 깁니다."),
	TENANT_MISMATCH(HttpStatus.FORBIDDEN, "이 이벤트에 입장할 수 없는 토큰입니다."),

	// --- 이벤트 -------------------------------------------------------------
	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
	EVENT_NOT_STARTED(HttpStatus.CONFLICT, "이벤트가 아직 시작되지 않았습니다."),

	// --- 세션 쿠키 (SessionAuthFilter) --------------------------------------
	SESSION_NOT_FOUND(HttpStatus.UNAUTHORIZED, "세션이 없거나 만료되었습니다."),
	SESSION_EVENT_MISMATCH(HttpStatus.FORBIDDEN, "다른 이벤트의 세션입니다."),

	// --- 발급 (claim, 플랜 4-5) — 품절은 에러가 아니라 200 + result: SOLD_OUT ----
	TICKET_REQUIRED(HttpStatus.CONFLICT, "번호표를 먼저 발급받아야 합니다."),
	ALREADY_CLAIMED(HttpStatus.CONFLICT, "이미 발급 처리된 사용자입니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}

}
