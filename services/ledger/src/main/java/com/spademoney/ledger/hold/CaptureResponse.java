package com.spademoney.ledger.hold;

/**
 * Wire-format result of capture. Stored verbatim for replay.
 *
 * releasedMinor is the part of the authorization that was NOT taken. Under the
 * one-capture model it is released implicitly: the hold leaves ACTIVE, so the
 * whole reserved amount stops counting against available balance and only
 * capturedMinor is actually posted.
 */
public record CaptureResponse(
        Long holdId,
        Long transactionId,
        long capturedMinor,
        long releasedMinor,
        String currency,
        String status) {
}
