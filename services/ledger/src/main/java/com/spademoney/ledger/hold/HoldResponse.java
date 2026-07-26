package com.spademoney.ledger.hold;

import java.time.OffsetDateTime;

/** Wire-format result of authorize and void. Stored verbatim for replay. */
public record HoldResponse(
        Long holdId,
        Long accountId,
        Long payeeAccountId,
        long amountMinor,
        String currency,
        String status,
        OffsetDateTime expiresAt) {
}
