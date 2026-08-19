package com.spademoney.payments;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Payments' own Postgres, entirely separate from the Ledger's test container.
 *
 * Payments' tests never start a Ledger, in a container or otherwise. The Ledger
 * is a mocked HTTP peer here (see PaymentApiTest); the real end-to-end proof is
 * the compose smoke test in Session 12. A test that boots both services would
 * be slow, would couple this module's build to the other's code, and would
 * still not prove the thing it looks like it proves, since it would run over
 * loopback rather than across the compose network that the chaos test kills.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

}
