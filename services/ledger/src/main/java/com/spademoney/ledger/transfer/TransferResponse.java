package com.spademoney.ledger.transfer;

/** Wire-format result of a successful transfer. Serialized and stored verbatim for idempotent replay. */
public record TransferResponse(Long transactionId, String status) {
}