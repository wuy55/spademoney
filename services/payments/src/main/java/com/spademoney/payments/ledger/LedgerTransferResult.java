package com.spademoney.payments.ledger;

/** The Ledger's POST /transfers 201 body. Duplicated deliberately — see {@link LedgerError}. */
public record LedgerTransferResult(Long transactionId, String status) {
}
