package io.tetra.issuance.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * application.yml 의 {@code tetra.*} 설정 (값은 values/values-{환경}.yml).
 * 값이 잘못되면 앱이 기동 단계에서 실패한다 — 잘못된 설정으로 트래픽을 받는 일을 막기 위함.
 */
@Validated
@ConfigurationProperties("tetra")
public record TetraProperties(
		@NotNull ZoneId timezone,
		@Valid @NotNull Session session,
		@Valid @NotNull Queue queue,
		@Valid @NotNull Event event,
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull Stock stock) {

	public record Session(
			/*
			 * 302 Location. "/" 로 시작하는 상대 경로만 허용.
			 * - 절대 주소 금지: 오리진이 받는 Host 가 오리진 주소라, 절대 주소/Host 기반 주소는 사용자를 오리진으로 보냄
			 * - "//host", "/\host" 금지: 브라우저가 다른 사이트 주소로 해석함 (오픈 리다이렉트)
			 * - 줄바꿈 금지: 응답 헤더 주입 방지
			 */
			@NotBlank
			@Pattern(regexp = "/(?![/\\\\])[^\\r\\n\\\\]*",
					message = "'/'로 시작하는 상대 경로여야 합니다 ('//', '\\', 절대 주소 불가)")
			String redirectUrl,
			@NotBlank String cookieName,
			@NotNull Duration ttl,
			boolean cookieSecure) {

		@AssertTrue(message = "ttl 은 0보다 커야 합니다")
		public boolean isTtlPositive() {
			return ttl == null || ttl.isPositive();
		}

	}

	public record Queue(
			@Positive int cursorStep,
			@NotNull Duration cursorWindow,
			@NotNull Duration cursorCacheSMaxage) {

		@AssertTrue(message = "cursor-window 는 1초 이상이어야 합니다")
		public boolean isCursorWindowValid() {
			return cursorWindow == null || cursorWindow.toSeconds() >= 1;
		}

		@AssertTrue(message = "cursor-cache-s-maxage 는 0초 이상이어야 합니다")
		public boolean isCursorCacheSMaxageValid() {
			return cursorCacheSMaxage == null || !cursorCacheSMaxage.isNegative();
		}

	}

	public record Event(
			/** 이벤트 정보 API 응답의 CDN 캐시 시간 (s-maxage) */
			@NotNull Duration infoCacheSMaxage) {

		@AssertTrue(message = "info-cache-s-maxage 는 0초 이상이어야 합니다")
		public boolean isInfoCacheSMaxageValid() {
			return infoCacheSMaxage == null || !infoCacheSMaxage.isNegative();
		}

	}

	public record Jwt(
			@Positive int userIdMaxLength,
			/** 테넌트 서버와의 시계 오차 허용치 (만료 판정에 더함) */
			@NotNull Duration clockSkew,
			/** exp 가 지금부터 이보다 멀면 거절 */
			@NotNull Duration maxTtl,
			/**
			 * 테스트 JWT 서명용 개인키 파일 경로 (실행 위치 기준). 로컬 전용이라 다른 환경에서는 비워 둘 수 있음.
			 * Path 로 받으면 Spring 이 "../" 상대 경로를 리소스 경로로 바꾸다 실패하므로 문자열로 받는다.
			 */
			String testPrivateKeyPath) {

		@AssertTrue(message = "clock-skew 는 0 이상 1분 이하여야 합니다")
		public boolean isClockSkewValid() {
			return clockSkew == null || (!clockSkew.isNegative() && clockSkew.compareTo(Duration.ofMinutes(1)) <= 0);
		}

		@AssertTrue(message = "max-ttl 은 0보다 커야 합니다")
		public boolean isMaxTtlPositive() {
			return maxTtl == null || maxTtl.isPositive();
		}

	}

	public record Stock(@NotNull WarmupMode warmupMode) {
	}

	/** 재고 워밍업 방식 (설정값 if-absent / force). */
	public enum WarmupMode {
		/** Redis 에 재고 키가 없을 때만 MySQL stock_count 로 채움 (재기동해도 차감분 유지) */
		IF_ABSENT,
		/** 항상 덮어씀 (로컬에서 재고를 처음 상태로 되돌릴 때만) */
		FORCE
	}

}
