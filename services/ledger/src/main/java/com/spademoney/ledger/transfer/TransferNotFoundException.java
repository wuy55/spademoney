package com.spademoney.ledger.transfer;

public class TransferNotFoundException extends RuntimeException {
	public TransferNotFoundException(long transactionId) {
		super("No transfer with id " + transactionId);
	}
}