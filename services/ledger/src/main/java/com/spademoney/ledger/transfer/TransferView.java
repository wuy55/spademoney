package com.spademoney.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /transfers/{id}. Exposes the postings, not just the id — double-entry is
 * the point of this system, so the API should show both sides.
 */
public record TransferView(
        Long transactionId,
        String status,
        OffsetDateTime createdAt,
        List<EntryView> entries) {
}