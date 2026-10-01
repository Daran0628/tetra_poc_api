package io.tetra.issuance.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Filter 에서 난 에러를 응답한다. Filter 는 Controller 바깥이라 GlobalExceptionHandler 가 잡지 못하므로,
 * 같은 형식(ApiResponse + 정확한 상태 코드 + no-store)을 여기서 직접 만든다.
 */
@Component
public class ErrorResponseWriter {

	private final JsonMapper jsonMapper;

	public ErrorResponseWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		response.setStatus(errorCode.status().value());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		jsonMapper.writeValue(response.getOutputStream(), ApiResponse.fail(errorCode));
	}

}
