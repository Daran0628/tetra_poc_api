package io.tetra.issuance.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Controller·Service 어디서 난 예외든 여기서 ApiResponse 형식으로 바꾼다.
 * 에러 응답은 항상 정확한 HTTP 상태 + {@code Cache-Control: no-store} (플랜 4절 공통 규칙):
 * CloudFront·브라우저가 에러를 캐시하지 않게 하고, 프론트의 res.ok 판단이 맞게 동작하도록.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
		return toResponse(e.errorCode());
	}

	@ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
			HttpMessageNotReadableException.class })
	ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
		return toResponse(ErrorCode.INVALID_REQUEST);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
		return toResponse(ErrorCode.NOT_FOUND);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
		return toResponse(ErrorCode.METHOD_NOT_ALLOWED);
	}

	/** 예상하지 못한 예외: 내부 정보는 응답에 넣지 않고 로그로만 남긴다. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		log.error("Unhandled exception", e);
		return toResponse(ErrorCode.INTERNAL_ERROR);
	}

	static ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.status())
				.cacheControl(CacheControl.noStore())
				.body(ApiResponse.fail(errorCode));
	}

}
