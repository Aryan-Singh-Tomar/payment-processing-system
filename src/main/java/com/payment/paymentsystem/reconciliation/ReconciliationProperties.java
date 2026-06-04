package com.payment.paymentsystem.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reconciliation configuration.
 *
 * @param stuckThresholdSeconds   how long a payment must be in PENDING/PROCESSING
 *                                before being considered stuck
 * @param scanIntervalMs          how often the scheduled sweeper runs
 * @param maxPerSweep             maximum payments to process per sweep
 * @param enabled                 master kill-switch for the job
 */
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(
        long stuckThresholdSeconds,
        long scanIntervalMs,
        int maxPerSweep,
        boolean enabled
) {
}
