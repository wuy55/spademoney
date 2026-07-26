package com.spademoney.ledger.hold;

/**
 * A capture may take less than was authorized, never more. Over-capturing would
 * post funds the authorization never reserved, breaking the invariant that lets
 * capture skip the funds check entirely.
 */
public class CaptureExceedsHoldException extends RuntimeException {
    public CaptureExceedsHoldException(long holdId, long requestedMinor, long authorizedMinor) {
        super("Capture of " + requestedMinor + " exceeds hold " + holdId
                + ", which authorized " + authorizedMinor);
    }
}
