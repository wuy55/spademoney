package com.spademoney.ledger.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs reconciliation on demand and returns the report.
 *
 * <h2>Always 200, even when the report is unhealthy</h2>
 * The request succeeded: the checks ran and produced an answer. Returning 500
 * because the ledger has a problem would conflate "reconciliation is broken"
 * with "reconciliation found something", and those need opposite responses.
 * The verdict is {@code healthy} in the body.
 *
 * <h2>Not behind /actuator</h2>
 * Actuator exposes health only, and deliberately: a chaos script needs one
 * machine-readable "is it back yet" signal, not a management surface. This is a
 * different question — "is the money right" — and answering it takes real
 * queries over the whole ledger, which is not something a liveness probe should
 * be doing every five seconds.
 */
@RestController
@RequestMapping("/reconciliation")
class ReconciliationController {

    private final LedgerReconciliationService reconciliation;

    ReconciliationController(LedgerReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    ResponseEntity<ReconciliationReport> reconcile() {
        return ResponseEntity.ok(reconciliation.reconcile());
    }
}
