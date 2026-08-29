package com.spademoney.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentsApplicationTests {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void contextLoadsAgainstRealPostgres() {
        assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void flywayCreatesPaymentsOwnSchema() {
        assertThat(jdbcClient.sql("SELECT count(*) FROM payment_limits").query(Integer.class).single())
                .isZero();
    }

    /**
     * Payments must not be able to see the Ledger's tables. In production that
     * is guaranteed by the two databases; here the container holds only
     * Payments' schema, so the same assertion proves the migration set is
     * self-contained -- if a Ledger migration ever leaked into this module's
     * Flyway locations, this fails.
     */
    @Test
    void theLedgersTablesAreNotVisibleFromPayments() {
        assertThat(jdbcClient.sql("SELECT to_regclass('public.accounts')")
                .query(String.class).optional()).isEmpty();
        assertThat(jdbcClient.sql("SELECT to_regclass('public.entries')")
                .query(String.class).optional()).isEmpty();
    }

    /** Payments keeps its own Flyway history; it never shares the Ledger's. */
    @Test
    void paymentsOwnsItsOwnFlywayHistory() {
        assertThat(jdbcClient.sql("SELECT script FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list())
                .containsExactly("V1__payment_limits.sql", "V2__inbox.sql");
    }
}
