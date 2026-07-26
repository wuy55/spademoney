package com.spademoney.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /transfers/{id}. Exposes the postings, not just the id — double-entry is
 * the point of this system, so the API should show both sides.
 *
 * Also serves captures and refunds, which are transactions like any other. That
 * is exactly why `type` and `reversesTransactionId` are on the wire: a capture
 * and a plain transfer post identical entries, so without `type` a client
 * cannot tell them apart after the fact. The database has enforced that
 * distinction since V2; withholding it here would mean the ledger knows the
 * provenance and no caller can find it out.
 */
public record TransferView(
        Long transactionId,
        String type,
        Long reversesTransactionId,
        String status,
        OffsetDateTime createdAt,
        List<EntryView> entries) {
}