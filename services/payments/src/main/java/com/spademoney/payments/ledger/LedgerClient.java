package com.spademoney.payments.ledger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The only code in Payments that knows the Ledger exists.
 *
 * Its whole job is to turn four possible outcomes of one HTTP call into four
 * distinguishable Java outcomes:
 *
 * <pre>
 *   2xx            -> LedgerTransferResult      the money moved
 *   4xx            -> LedgerRejectedException   the money definitively did not move, and why
 *   5xx / refused  -> LedgerUnavailableException the request was not processed
 *   read timeout   -> LedgerTimeoutException    nobody knows (see that class)
 * </pre>
 *
 * Collapsing the last two into one "the call failed" is the mistake this class
 * exists to avoid: they differ in whether a retry is safe, which is the only
 * question that matters once the saga arrives.
 */
@Component
public class LedgerClient {

    private final RestClient restClient;

    LedgerClient(RestClient ledgerRestClient) {
        this.restClient = ledgerRestClient;
    }

    /**
     * @param idempotencyKey a key in the *Ledger's* scope, derived by
     *                       {@link LedgerIdempotencyKeys} — never the caller's.
     */
    public LedgerTransferResult transfer(LedgerTransferCommand command, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/transfers")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(command)
                    // exchange() rather than retrieve(): the error body carries the
                    // Ledger's error code, and it is the payload, not an exceptional
                    // detail to be recovered from a wrapper exception.
                    .exchange(this::translate);
        } catch (ResourceAccessException ex) {
            throw transportFailure(ex);
        }
    }

    private LedgerTransferResult translate(
            org.springframework.http.HttpRequest request,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {

        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            LedgerTransferResult result = response.bodyTo(LedgerTransferResult.class);
            if (result == null || result.transactionId() == null) {
                // A 2xx we cannot read is not a success we can report. Treat it as
                // ambiguous rather than inventing a transaction id.
                throw new LedgerUnavailableException(
                        "Ledger returned " + status + " with an unreadable body");
            }
            return result;
        }

        if (status.is4xxClientError()) {
            LedgerError error = response.bodyTo(LedgerError.class);
            throw new LedgerRejectedException(status,
                    error == null || error.code() == null ? LedgerError.unparseable() : error);
        }

        throw new LedgerUnavailableException("Ledger returned " + status);
    }

    /**
     * RestClient wraps every transport-level IOException in the same
     * ResourceAccessException, so the distinction that matters — was the request
     * processed? — has to be recovered from the cause chain.
     *
     * <h2>A connect timeout is not a read timeout</h2>
     * They look alike and mean opposite things. Failing to connect means no
     * bytes reached the Ledger, so the money certainly did not move and a retry
     * is safe: 502. Connecting and then hearing nothing means the request may
     * have been processed and the answer lost: 504, and nobody may retry it.
     *
     * The ordering below is load-bearing.
     * {@link HttpConnectTimeoutException} <em>extends</em>
     * {@link HttpTimeoutException}, so testing the general case first silently
     * reports every unreachable Ledger as ambiguous. That is precisely the bug
     * this method shipped with: stopping the Ledger's container produced a 504
     * after exactly the two-second connect timeout, claiming not to know
     * something it definitely knew.
     */
    private RuntimeException transportFailure(ResourceAccessException ex) {
        for (Throwable cause = ex.getCause(); cause != null && cause != cause.getCause();
                cause = cause.getCause()) {

            // Never got a connection: refused, unroutable, or timed out dialling.
            // The request was not processed.
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new LedgerUnavailableException(
                        "Ledger could not be reached; the transfer was not attempted", ex);
            }

            // Connected, then silence. Whether it posted is unknown -- see
            // LedgerTimeoutException for why that is left unresolved.
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new LedgerTimeoutException(
                        "Ledger did not respond within the read timeout; "
                                + "whether the transfer posted is unknown",
                        ex);
            }
        }
        return new LedgerUnavailableException("Ledger could not be reached", ex);
    }
}
