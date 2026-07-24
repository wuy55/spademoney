package com.spademoney.ledger.account;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spademoney.ledger.query.LedgerQueryService;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final LedgerQueryService queries;

    public AccountController(LedgerQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{id}/balance")
    public BalanceView getBalance(@PathVariable long id) {
        return queries.findBalance(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}