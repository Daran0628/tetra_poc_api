package io.tetra.issuance.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TetraProperties.class)
public class AppConfig {

	/**
	 * 현재 시각은 항상 이 Clock 으로 얻는다 (LocalDateTime.now() 직접 호출 금지).
	 * - 타임존: tetra.timezone (DB 저장 타임존 KST 와 같음)
	 * - 테스트에서는 Clock.fixed(...) 로 바꿔 "이벤트 시작 전" 같은 시각을 재현
	 */
	@Bean
	Clock clock(TetraProperties properties) {
		return Clock.system(properties.timezone());
	}

}
