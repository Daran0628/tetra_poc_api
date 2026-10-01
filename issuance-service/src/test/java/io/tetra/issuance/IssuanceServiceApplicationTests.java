package io.tetra.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IssuanceServiceApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void 테스트_DB에_DB정의서_스키마와_시드가_들어간다() {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT tenant_id FROM event WHERE event_id = 1", String.class))
				.isEqualTo("poctenant001");
		assertThat(jdbcTemplate.queryForObject("SELECT SUM(stock_count) FROM coupon", Integer.class)).isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM issuance_history", Integer.class)).isZero();
	}

	@Test
	void 테스트_DB_타임존은_KST다() {
		assertThat(jdbcTemplate.queryForObject("SELECT @@time_zone", String.class)).isEqualTo("+09:00");
	}

	@Test
	void Redis에_연결된다() {
		redisTemplate.opsForValue().set("test:ping", "pong");
		assertThat(redisTemplate.opsForValue().get("test:ping")).isEqualTo("pong");
	}

}
