package com.yourorg.payops.service;

import com.yourorg.payops.client.bank.BankAuthResponse;
import com.yourorg.payops.client.bank.FakeBankClient;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@Service
public class PaymentRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final FakeBankClient bankClient;
    private final LedgerService ledgerService;
    private final TransactionTemplate transactionTemplate;

    public PaymentRecoveryService(PaymentRepository payments,
                                  PaymentEventRepository events,
                                  FakeBankClient bankClient,
                                  LedgerService ledgerService,
                                  TransactionTemplate transactionTemplate) {
        this.payments = payments;
        this.events = events;
        this.bankClient = bankClient;
        this.ledgerService = ledgerService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Recovers a batch of PENDING payments by querying fake-bank status for each.
     * Database transactions are never held open during the HTTP calls.
     *
     * @param batchSize maximum number of pending payments to inspect per cycle
     * @return count of payments transitioned to a terminal status (APPROVED or DECLINED)
     */
    public int recoverBatch(int batchSize) {
        int safeLimit = Math.max(1, Math.min(batchSize, 500));
        List<Payment> pendingList = payments.findByStatusAndBankRequestIdIsNotNullOrderByCreatedAtAsc(
                PaymentStatus.PENDING, PageRequest.of(0, safeLimit)
        );

        if (pendingList.isEmpty()) {
            return 0;
        }

        log.debug("Found {} pending payments to inspect for recovery", pendingList.size());
        int resolvedCount = 0;

        for (Payment payment : pendingList) {
            try {
                boolean resolved = recoverPendingPayment(payment.getId());
                if (resolved) {
                    resolvedCount++;
                }
            } catch (Exception e) {
                log.error("Error during recovery processing for payment id={}: {}", payment.getId(), e.getMessage(), e);
            }
        }

        return resolvedCount;
    }

    /**
     * Inspects a single pending payment and queries the fake bank using its persisted bankRequestId.
     * Strictly avoids holding a database transaction open during the external HTTP request.
     *
     * @param paymentId payment ID to recover
     * @return true if this call performed the transition to APPROVED or DECLINED; false if still PENDING, skipped, or already transitioned
     */
    public boolean recoverPendingPayment(Long paymentId) {
        Optional<Payment> opt = payments.findById(paymentId);
        if (opt.isEmpty()) {
            return false;
        }

        Payment payment = opt.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.debug("Payment id={} is no longer PENDING (status={}). Skipping recovery.", paymentId, payment.getStatus());
            return false;
        }

        String bankRequestId = payment.getBankRequestId();
        if (bankRequestId == null || bankRequestId.isBlank()) {
            log.warn("Payment id={} is PENDING but lacks bankRequestId. Cannot poll bank.", paymentId);
            return false;
        }

        // 1. External HTTP call executed OUTSIDE any database transaction
        Optional<BankAuthResponse> bankResponseOpt;
        try {
            bankResponseOpt = bankClient.getStatus(bankRequestId);
        } catch (RestClientException e) {
            log.warn("Network/timeout error querying fake-bank for payment id={} (requestId={}): {}. Will retry safely on next poll.",
                    paymentId, bankRequestId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error querying fake-bank for payment id={}: {}. Will retry safely on next poll.",
                    paymentId, e.getMessage());
            return false;
        }

        if (bankResponseOpt.isEmpty()) {
            log.warn("Fake-bank returned 404 for requestId={}. Payment id={} will remain PENDING for subsequent retry.",
                    bankRequestId, paymentId);
            return false;
        }

        BankAuthResponse bankResponse = bankResponseOpt.get();
        String bankStatus = bankResponse.getStatus();

        // 2. Atomic state transition in a dedicated transaction via TransactionTemplate
        if ("APPROVED".equalsIgnoreCase(bankStatus)) {
            log.info("Recovery resolved payment id={} to APPROVED (bankRef={})", paymentId, bankResponse.getBankReference());
            return Boolean.TRUE.equals(applyRecoveryApproval(paymentId, bankResponse.getBankReference()));
        } else if ("DECLINED".equalsIgnoreCase(bankStatus)) {
            String reason = (bankResponse.getResponseMessage() != null && !bankResponse.getResponseMessage().isBlank())
                    ? bankResponse.getResponseMessage()
                    : "Declined by bank";
            log.info("Recovery resolved payment id={} to DECLINED (reason={})", paymentId, reason);
            return Boolean.TRUE.equals(applyRecoveryDecline(paymentId, bankResponse.getBankReference(), reason));
        } else {
            log.debug("Payment id={} is still PENDING at bank (status={}). Will poll again.", paymentId, bankStatus);
            return false;
        }
    }

    /**
     * Atomically transitions payment to APPROVED, saves PaymentEvent, and creates ledger postings.
     * Uses PESSIMISTIC_WRITE lock on Payment row to prevent duplicate transitions from concurrent workers.
     *
     * @return true if this execution successfully transitioned the payment; false if it was already transitioned
     */
    public boolean applyRecoveryApproval(Long paymentId, String bankReference) {
        Boolean result = transactionTemplate.execute(status -> {
            Payment payment = payments.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

            // Recheck under exclusive lock
            if (payment.getStatus() != PaymentStatus.PENDING) {
                log.info("Payment id={} already transitioned to {} by another worker. Skipping approval update.",
                        paymentId, payment.getStatus());
                return false;
            }

            payment.setStatus(PaymentStatus.APPROVED);
            payment.setBankReference(bankReference);
            payment.setSwitchReference(bankReference);
            payment.setFailureReason(null);
            Payment updated = payments.save(payment);

            // Safe idempotent PaymentEvent creation
            if (!events.existsByPaymentIdAndEventType(paymentId, PaymentStatus.APPROVED)) {
                PaymentEvent approvedEvent = new PaymentEvent();
                approvedEvent.setPayment(updated);
                approvedEvent.setEventType(PaymentStatus.APPROVED);
                approvedEvent.setMessage("Payment approved via background recovery (ref: " + bankReference + ")");
                events.save(approvedEvent);
            }

            // Exactly-once balanced ledger posting: Dr 1100, Cr 2100
            ledgerService.recordPaymentApproval(updated);

            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /**
     * Atomically transitions payment to DECLINED and saves PaymentEvent without ledger postings.
     * Uses PESSIMISTIC_WRITE lock on Payment row to prevent race conditions.
     *
     * @return true if this execution successfully transitioned the payment; false if it was already transitioned
     */
    public boolean applyRecoveryDecline(Long paymentId, String bankReference, String reason) {
        Boolean result = transactionTemplate.execute(status -> {
            Payment payment = payments.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

            // Recheck under exclusive lock
            if (payment.getStatus() != PaymentStatus.PENDING) {
                log.info("Payment id={} already transitioned to {} by another worker. Skipping decline update.",
                        paymentId, payment.getStatus());
                return false;
            }

            payment.setStatus(PaymentStatus.DECLINED);
            payment.setBankReference(bankReference);
            payment.setFailureReason(reason);
            Payment updated = payments.save(payment);

            // Safe idempotent PaymentEvent creation
            if (!events.existsByPaymentIdAndEventType(paymentId, PaymentStatus.DECLINED)) {
                PaymentEvent declinedEvent = new PaymentEvent();
                declinedEvent.setPayment(updated);
                declinedEvent.setEventType(PaymentStatus.DECLINED);
                declinedEvent.setMessage("Payment declined via background recovery: " + reason);
                events.save(declinedEvent);
            }

            // Ledger postings are strictly NOT created for declines
            return true;
        });
        return Boolean.TRUE.equals(result);
    }
}
