package com.payment.paymentsystem.exception;

public class InvalidPaymentRequestException extends RuntimeException {
    public InvalidPaymentRequestException(String message) {
        super(message);
    }
}
