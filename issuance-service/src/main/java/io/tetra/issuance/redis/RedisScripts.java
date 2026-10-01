package io.tetra.issuance.redis;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/** Lua 스크립트 등록. 스크립트 본문은 resources/redis/scripts/*.lua 에 파일로 둔다 (플랜 7절). */
@Configuration(proxyBeanMethods = false)
public class RedisScripts {

	@Bean
	RedisScript<Long> ticketIssueScript() {
		return RedisScript.of(new ClassPathResource("redis/scripts/ticket-issue.lua"), Long.class);
	}

	@Bean
	RedisScript<Long> cursorAdvanceScript() {
		return RedisScript.of(new ClassPathResource("redis/scripts/cursor-advance.lua"), Long.class);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Bean
	RedisScript<List<Long>> couponClaimScript() {
		return (RedisScript) RedisScript.of(new ClassPathResource("redis/scripts/coupon-claim.lua"), List.class);
	}

}
