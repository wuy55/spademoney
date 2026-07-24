package com.spademoney.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

}
