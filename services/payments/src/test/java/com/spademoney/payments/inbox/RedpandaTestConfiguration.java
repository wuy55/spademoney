package com.spademoney.payments.inbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * A real broker for the consumer tests, and only for them.
 *
 * The rest of this module runs with the listener disabled (see
 * src/test/resources/application.yml). Booting a broker for tests about HTTP
 * translation would add container-start time to every one of them and prove
 * nothing they are about.
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
