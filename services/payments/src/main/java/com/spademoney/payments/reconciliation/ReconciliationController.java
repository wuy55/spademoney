package com.spademoney.payments.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs Payments' reconciliation on demand.
 *
 * Always 200: the checks ran and produced an answer, which is what the request
 * asked for. Whether the answer is good is {@code healthy} in the body. A 500
 * here would mean "reconciliation itself is broken", and conflating that with
 * "reconciliation found something" makes both unactionable.
 */
@RestController
@RequestMapping("/reconciliation")
class ReconciliationController {

    private final PaymentsReconciliationService reconciliation;

    ReconciliationController(PaymentsReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    ResponseEntity<ReconciliationReport> reconcile() {
        return ResponseEntity.ok(reconciliation.reconcile());
    }
}
