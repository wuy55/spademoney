package com.spademoney.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerApplicationTests {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void contextLoadsAgainstRealPostgres() {
        assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
    }
}
