package com.spademoney.ledger.outbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads fields out of an event payload without caring how the JSON is laid out.
 *
 * <h2>Why not just assert on the string</h2>
 * Because the string is not the one Jackson wrote. The payload column is
 * {@code jsonb}, and jsonb is a parsed representation, not a copy of the text:
 * Postgres reorders the keys and normalises the whitespace, so a payload written
 * as {@code {"transactionId":2,"currency":"USD"}} reads back as
 * {@code {"currency": "USD", "transactionId": 2}}. Substring assertions against
 * the serialized form therefore fail for a reason that has nothing to do with
 * the event being wrong.
 *
 * That normalisation is accepted deliberately rather than worked around: see the
 * note on the payload column in {@code V3__outbox.sql}. Consumers parse the JSON,
 * so key order carries no meaning, and in exchange every outbox row is validated
 * as JSON by the insert itself — inside the same transaction as the money.
 */
final class EventPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventPayloads() {
    }

    static long longField(String payload, String field) {
        return node(payload, field).asLong();
    }

    static String stringField(String payload, String field) {
        return node(payload, field).asString();
    }

    private static JsonNode node(String payload, String field) {
        JsonNode node = MAPPER.readTree(payload).get(field);
        if (node == null) {
            throw new AssertionError("No field '" + field + "' in payload " + payload);
        }
        return node;
    }
}
