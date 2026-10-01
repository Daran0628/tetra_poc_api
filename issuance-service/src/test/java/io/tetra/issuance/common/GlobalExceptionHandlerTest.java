package io.tetra.issuance.common;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 공통 응답 형식과 에러 응답 규칙(정확한 상태 코드 + no-store) 검증. 컨테이너 없이 MockMvc 단독 구성. */
class GlobalExceptionHandlerTest {

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void 성공_응답은_data만_있고_error는_없다() throws Exception {
		mockMvc.perform(get("/test/ok"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.value").value(42))
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void 비즈니스_예외는_ErrorCode의_상태코드와_코드로_응답하고_no_store다() throws Exception {
		mockMvc.perform(get("/test/business"))
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("EVENT_NOT_STARTED"))
				.andExpect(jsonPath("$.error.message").value(ErrorCode.EVENT_NOT_STARTED.message()))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void 경로_변수_타입이_틀리면_400() throws Exception {
		mockMvc.perform(get("/test/events/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void 없는_경로는_index_html이_아니라_404_JSON() throws Exception {
		mockMvc.perform(get("/test/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 허용되지_않은_메서드는_405() throws Exception {
		mockMvc.perform(post("/test/ok"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void 예상하지_못한_예외는_500이고_내부_메시지를_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-internal-detail"))));
	}

	@RestController
	static class TestController {

		@GetMapping("/test/ok")
		ApiResponse<Value> ok() {
			return ApiResponse.ok(new Value(42));
		}

		@GetMapping("/test/business")
		ApiResponse<Void> business() {
			throw new BusinessException(ErrorCode.EVENT_NOT_STARTED);
		}

		@GetMapping("/test/events/{eventId}")
		ApiResponse<Value> event(@PathVariable long eventId) {
			return ApiResponse.ok(new Value(eventId));
		}

		@GetMapping("/test/boom")
		ApiResponse<Void> boom() {
			throw new IllegalStateException("secret-internal-detail");
		}

		record Value(long value) {
		}

	}

}
