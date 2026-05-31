package com.payment.paymentsystem.service;

import com.payment.paymentsystem.entity.Payment;
import com.payment.paymentsystem.entity.PaymentStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class PaymentStateMachine {

    private static final Set<PaymentStatus> NON_TERMINAL = EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING);
    private static final Set<PaymentStatus> TERMINAL_SUCCESS = EnumSet.of(PaymentStatus.SUCCESS);
    private static final Set<PaymentStatus> TERMINAL_FAILURE = EnumSet.of(PaymentStatus.UNKNOWN, PaymentStatus.FAILED);

    /**
     *         Determines whether the order is in a state that accepts a new payment.
     *
     *         @return Optional.empty() if a new payment is allowed.
     *         Optional.of(reason) if blocked, with a human-readable reason.
     **/

    public Optional<String> canCreateNewPayment(List<Payment> existingPayments){
        for (Payment p : existingPayments){
            if (NON_TERMINAL.contains(p.getStatus())){
                return Optional.of(
                        "Order already has a payment in progress (paymentId=" +
                                p.getId() + ", status=" + p.getStatus() + ")"
                );
            } else if(TERMINAL_SUCCESS .contains(p.getStatus())){
                return Optional.of(
                        "Order has already been paid successfully (paymentId=" +
                                p.getId() + ")"
                );
            }
        }
        return Optional.empty();
    }

    /**
     * Returns true if a payment can transition from one status to another.
     * Not used by Day 24 directly but provided for future refactoring of
     * markProcessing and recordResult to centralize transition rules here.
     */
    public boolean canTransition(PaymentStatus from, PaymentStatus to){
        if(isTerminal(from)){
            return false; // no transitions out of terminal states
        }
        return switch (from) {
            case PENDING -> to == PaymentStatus.PROCESSING;
            case PROCESSING -> to == PaymentStatus.SUCCESS
                    || to == PaymentStatus.FAILED
                    || to == PaymentStatus.UNKNOWN;
            default -> false;
        };
    }

    public boolean isTerminal(PaymentStatus status){
        return TERMINAL_SUCCESS.contains(status) || TERMINAL_FAILURE.contains(status);
    }


}
