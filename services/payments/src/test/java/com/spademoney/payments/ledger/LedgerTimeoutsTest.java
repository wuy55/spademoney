package com.spademoney.payments.ledger;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.spademoney.payments.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two properties that keep a dead Ledger from becoming a hung
 * request.
 *
 * Asserting configuration values usually earns nothing, and this test is the
 * exception that proves why the rule has one. Spring's default HTTP client
 * timeout is effectively infinite, and nothing else in the build notices if
 * these lines are deleted -- every existing test would stay green while
 * Session 12's chaos demo quietly turned into a terminal that never returns.
 * The failure mode is invisible until the exact moment it is most expensive, so
 * it gets an explicit assertion.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerTimeoutsTest {

    @Autowired
    private Environment environment;

    @Test
    void theLedgerClientHasAnExplicitConnectTimeout() {
        assertThat(environment.getProperty("spring.http.clients.connect-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theLedgerClientHasAnExplicitReadTimeout() {
        assertThat(environment.getProperty("spring.http.clients.read-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(5));
    }

    /**
     * The read timeout must exceed the connect timeout. Not a tautology: they
     * are two independent numbers in a yaml file, and a read timeout shorter
     * than the connect timeout would make a slow-but-reachable Ledger
     * indistinguishable from an absent one.
     */
    @Test
    void theReadTimeoutIsTheLongerOfTheTwo() {
        Duration connect = environment.getProperty("spring.http.clients.connect-timeout", Duration.class);
        Duration read = environment.getProperty("spring.http.clients.read-timeout", Duration.class);
        assertThat(read).isGreaterThan(connect);
    }
}
