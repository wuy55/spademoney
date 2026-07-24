package com.spademoney.ledger.account;

public class AccountNotFoundException extends RuntimeException {
	public AccountNotFoundException(long accountId) {
		super("No account with id " + accountId);
	}
}