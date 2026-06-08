package com.payment.paymentsystem.observability;

/**
 * Utility for masking sensitive values in log messages.
 *
 * The goal is not cryptographic secrecy — anyone with database access could
 * read these values directly. The goal is operational hygiene: logs are
 * frequently shipped to third-party log aggregation tools, shared in
 * support tickets, pasted in chat, etc. Masking ensures sensitive
 * identifiers don't accidentally leak through those channels.
 *
 * Pattern: keep the first 4 and last 4 characters, replace the middle with
 * asterisks. This is enough to:
 *   - Visually correlate two log lines as the same entity.
 *   - Trace through a sequence of events.
 *   - But NOT reconstruct the full value from logs alone.
 *
 * Examples:
 *   mask("cid-test-001")             -> "cid-***-001"
 *   mask("CUST-ABC123XYZ")           -> "CUST****XYZ"
 *   mask("gw_eda75d834abb4f7a")      -> "gw_e********4f7a"
 *   mask(null)                       -> "null"
 *   mask("abc")                      -> "***"   (too short to leave any tail)
 */
public final class LogMasking {

    private static final int KEEP_PREFIX = 4;
    private static final int KEEP_SUFFIX = 4;
    private static final int MIN_LENGTH_TO_PARTIALLY_REVEAL = KEEP_PREFIX + KEEP_SUFFIX + 2;

    private LogMasking() {
    }

    /**
     * Mask a sensitive value for logging. Short values are fully masked.
     */
    public static String mask(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() < MIN_LENGTH_TO_PARTIALLY_REVEAL) {
            return "***";
        }
        return value.substring(0, KEEP_PREFIX)
                + "*".repeat(value.length() - KEEP_PREFIX - KEEP_SUFFIX)
                + value.substring(value.length() - KEEP_SUFFIX);
    }
}