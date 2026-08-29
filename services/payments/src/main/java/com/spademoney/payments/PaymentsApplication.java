package com.spademoney.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Payments (orchestrator) service.
 *
 * It accepts a payment, writes down a saga, and drives that saga to a terminal
 * state: authorize a hold in the Ledger, consume the payer's local spending cap,
 * capture the hold — compensating in reverse if any of it fails. It owns no
 * money and holds no ledger state; the Ledger remains the sole authority on
 * balances (ADR-002). The only thing it owns outright is the cap, which is
 * precisely why the saga needs a compensation at all.
 *
 * It exists now, ahead of doing anything interesting, because the seam is the
 * artifact: two processes, two databases, no shared code, and therefore no
 * possibility of a distributed transaction. Everything M3 adds afterwards —
 * outbox, inbox, saga, compensation, reconciliation — is a consequence of that
 * seam rather than a decoration on top of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// Without this the saga driver's @Scheduled trigger is never registered and
// every payment sits in RUNNING forever. Nothing in the unit suite can catch
// that, because those tests call SagaDriver.runOnce() themselves --
// SchedulingIsWiredTest exists specifically to close that gap.
@EnableScheduling
public class PaymentsApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentsApplication.class, args);
	}

}
