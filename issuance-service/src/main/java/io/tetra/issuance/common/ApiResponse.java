package io.tetra.issuance.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API의 공통 응답 형식 (ADR-0002 결정 6).
 *
 * <pre>
 * 성공: {"success": true,  "data": {...}}
 * 실패: {"success": false, "error": {"code": "JWT_EXPIRED", "message": "..."}}
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static ApiResponse<Void> fail(ErrorCode errorCode) {
		return new ApiResponse<>(false, null, new ErrorBody(errorCode.name(), errorCode.message()));
	}

	public record ErrorBody(String code, String message) {
	}

}
