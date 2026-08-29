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
 *   2xx            -> the parsed result          the command took effect
 *   4xx            -> LedgerRejectedException    it definitively did not, and why
 *   5xx / refused  -> LedgerUnavailableException the request was not processed
 *   read timeout   -> LedgerTimeoutException     nobody knows (see that class)
 * </pre>
 *
 * Collapsing the last two into one "the call failed" is the mistake this class
 * exists to avoid: they differ in whether a retry is safe, which is the only
 * question the saga driver ever asks.
 *
 * <h2>One generic method, three commands</h2>
 * Authorize, capture and void differ only in path, body and response type. The
 * translation above is identical for all three and is the part that is easy to
 * get subtly wrong, so it lives in one place rather than being copied per
 * endpoint. This is deliberately not a "Ledger SDK": it knows nothing about
 * holds, and the command shapes it posts are declared next to the steps that
 * use them.
 */
@Component
public class LedgerClient {

    private final RestClient restClient;

    LedgerClient(RestClient ledgerRestClient) {
        this.restClient = ledgerRestClient;
    }

    /**
     * @param uri            the Ledger path, e.g. {@code /holds/7/capture}
     * @param command        the request body, already built and (from the saga)
     *                       already persisted. Null for endpoints that take
     *                       none, such as void, whose Ledger-side fingerprint is
     *                       derived from the path.
     * @param idempotencyKey a key in the *Ledger's* scope, derived by
     *                       {@link LedgerIdempotencyKeys} — never the caller's.
     * @param responseType   the shape of the 2xx body
     */
    public <T> T post(String uri, Object command, String idempotencyKey, Class<T> responseType) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(uri)
                    .header("Idempotency-Key", idempotencyKey);

            RestClient.RequestHeadersSpec<?> spec = command == null ? request : request.body(command);

            // exchange() rather than retrieve(): the error body carries the
            // Ledger's error code, and it is the payload, not an exceptional
            // detail to be recovered from a wrapper exception.
            return spec.exchange((req, response) -> translate(response, responseType));
        } catch (ResourceAccessException ex) {
            throw transportFailure(ex);
        }
    }

    /**
     * A read. No Idempotency-Key, because nothing changes.
     *
     * Used by the compensation path to ask what state a hold is actually in --
     * see {@code VoidStep}. Note this is a query about a resource, not a
     * "did my write land?" probe: that probe was rejected in session 6 because
     * it races an in-flight commit, and the deterministic step key removed the
     * need for it entirely.
     */
    public <T> T get(String uri, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> translate(response, responseType));
        } catch (ResourceAccessException ex) {
            throw transportFailure(ex);
        }
    }

    private <T> T translate(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Class<T> responseType) throws IOException {

        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            T result = response.bodyTo(responseType);
            if (result == null) {
                // A 2xx we cannot read is not a success we can report. Treat it as
                // ambiguous rather than inventing a result.
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
