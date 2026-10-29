package com.yourorg.payops.repository;

import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    List<PaymentEvent> findByPaymentIdOrderByOccurredAtAsc(Long paymentId);
    boolean existsByPaymentIdAndEventType(Long paymentId, PaymentStatus eventType);
    List<PaymentEvent> findByPaymentIdAndEventType(Long paymentId, PaymentStatus eventType);
}
