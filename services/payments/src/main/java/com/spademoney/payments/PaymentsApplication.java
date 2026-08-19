package com.spademoney.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Payments (orchestrator) service.
 *
 * Today it is a proxy: POST /payments validates, then forwards one transfer to
 * the Ledger over HTTP. It owns no money and holds no ledger state — the Ledger
 * remains the sole authority on balances (ADR-002).
 *
 * It exists now, ahead of doing anything interesting, because the seam is the
 * artifact: two processes, two databases, no shared code, and therefore no
 * possibility of a distributed transaction. Everything M3 adds afterwards —
 * outbox, inbox, saga, compensation, reconciliation — is a consequence of that
 * seam rather than a decoration on top of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentsApplication.class, args);
	}

}
