package io.tetra.issuance.common;

/**
 * 비즈니스 규칙 위반을 알리는 예외. 어느 계층에서 던져도 GlobalExceptionHandler가
 * ErrorCode의 HTTP 상태와 ApiResponse 형식으로 바꿔 응답한다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

}
