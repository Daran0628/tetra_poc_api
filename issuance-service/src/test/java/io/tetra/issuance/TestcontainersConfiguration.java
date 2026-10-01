package io.tetra.issuance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트용 MySQL·Valkey 컨테이너.
 * 이미지·DB 이름·타임존·초기 SQL을 PoC/docker-compose.yml 과 같게 맞춘다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** docker-compose 가 쓰는 init SQL 폴더 (01_schema → 02_seed → 03_local_test_key). 실행 위치는 issuance-service 폴더. */
	private static final String DB_INIT_DIR = "../db/init";

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.0"))
				.withDatabaseName("tetra_poc")
				.withCopyFileToContainer(MountableFile.forHostPath(DB_INIT_DIR), "/docker-entrypoint-initdb.d/")
				.withCommand("--default-time-zone=+09:00",
						"--character-set-server=utf8mb4",
						"--collation-server=utf8mb4_0900_ai_ci")
				.withUrlParam("connectionTimeZone", "Asia/Seoul")
				.withUrlParam("characterEncoding", "UTF-8");
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("valkey/valkey:7.2")).withExposedPorts(6379);
	}

}
