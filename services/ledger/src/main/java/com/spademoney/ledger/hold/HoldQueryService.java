package com.spademoney.ledger.hold;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for holds. Pure query -- no locking, no mutation.
 *
 * Kept out of HoldService so the write path stays only the parts that can
 * change money. It reuses HoldService::mapHold rather than re-listing the
 * columns, so a hold looks identical whether it was just created or fetched
 * later; two mappers would be two places to forget a field.
 *
 * The status returned is the STORED one, which for a lapsed hold still reads
 * ACTIVE until the sweeper relabels it. That is faithful rather than sloppy:
 * expiry is decided by comparing expires_at to now(), and a client that wants
 * "is this still usable" should read expires_at rather than trust the label.
 */
@Service
@Transactional(readOnly = true)
public class HoldQueryService {

    private final JdbcClient jdbcClient;

    public HoldQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<HoldResponse> findHold(long holdId) {
        return jdbcClient.sql("""
                SELECT id, account_id, payee_account_id, amount_minor, currency, status, expires_at
                  FROM holds WHERE id = ?
                """)
                .param(holdId)
                .query(HoldService::mapHold)
                .optional();
    }
}
