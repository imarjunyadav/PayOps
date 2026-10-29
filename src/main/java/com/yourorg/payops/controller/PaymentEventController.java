package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.security.PayOpsUserDetails;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments/{paymentId}/events")
public class PaymentEventController {
    private final PaymentRepository payments;
    private final PaymentEventRepository events;

    public PaymentEventController(PaymentRepository payments, PaymentEventRepository events) {
        this.payments = payments;
        this.events = events;
    }

    @GetMapping
    public List<PaymentEvent> listForPayment(@PathVariable Long paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PayOpsUserDetails principal) {
            if (principal.isMerchant() && !principal.isAdmin()) {
                Long userMerchantId = principal.getMerchantId();
                if (userMerchantId == null || payment.getMerchant() == null || !payment.getMerchant().getId().equals(userMerchantId)) {
                    throw new AccessDeniedException("Access denied: cannot access events for another merchant's payment");
                }
            }
        }

        return events.findByPaymentIdOrderByOccurredAtAsc(paymentId);
    }
}
