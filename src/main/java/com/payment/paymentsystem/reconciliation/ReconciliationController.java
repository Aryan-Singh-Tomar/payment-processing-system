package com.payment.paymentsystem.reconciliation;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Hidden
@RestController
@RequestMapping("/api/internal/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /**
     * Returns the current count of stuck payments.
     * Useful for monitoring dashboards and on-call investigation.
     */
    @GetMapping("/stuck-count")
    public Map<String, Integer> stuckCount() {
        return Map.of("count", service.countStuckPayments());
    }


    /**
     * Triggers a sweep on demand (for testing / manual investigation).
     * In production this would typically require auth; we leave it open for the dev/test environment.
     */
    @PostMapping("/run-sweep")
    public Map<String, String> runSweep() {
        service.sweep();
        return Map.of("status", "sweep triggered");
    }

}
