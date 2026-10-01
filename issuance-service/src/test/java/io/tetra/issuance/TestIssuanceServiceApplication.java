package io.tetra.issuance;

import org.springframework.boot.SpringApplication;

public class TestIssuanceServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(IssuanceServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
