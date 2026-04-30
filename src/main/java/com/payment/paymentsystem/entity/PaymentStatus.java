package com.payment.paymentsystem.entity;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
