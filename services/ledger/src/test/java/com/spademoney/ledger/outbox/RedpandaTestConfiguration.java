package com.spademoney.ledger.outbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A real broker, for the one test that needs one.
 *
 * Imported by {@link OutboxRelayIntegrationTest} alone. Every other test in this
 * module runs without a broker: the relay's ordering and failure behaviour is
 * proven against a recording publisher, and making 130-odd tests wait on a
 * container start would buy nothing but minutes.
 *
 * The image is pinned rather than floating on a tag. A broker that silently
 * changes version between runs turns "the test went red" into a research
 * project.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedpandaTestConfiguration {

    private static final RedpandaContainer REDPANDA =
            new RedpandaContainer("redpandadata/redpanda:v24.2.7");

    @Bean
    @ServiceConnection
    RedpandaContainer redpandaContainer() {
        return REDPANDA;
    }
}
