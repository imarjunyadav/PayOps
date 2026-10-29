package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.entity.BankTransaction;
import com.yourorg.fakebank.exception.BankIdempotencyConflictException;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class BankPaymentService {
    private static final Logger log = LoggerFactory.getLogger(BankPaymentService.class);

    private final BankTransactionRepository transactions;
    private final BankTransactionClaimService claimService;
    private final long delayMs;

    public BankPaymentService(BankTransactionRepository transactions,
                              BankTransactionClaimService claimService,
                              @Value("${fakebank.delayed-card.delay-ms:30000}") long delayMs) {
        this.transactions = transactions;
        this.claimService = claimService;
        this.delayMs = delayMs;
    }

    /**
     * Concurrency-safe payment authorization.
     * Guaranteed: Exactly one debit occurs even under heavy concurrent duplicate requests.
     */
    public BankPaymentResponse authorize(BankPaymentRequest request) {
        String requestId = request.getRequestId();
        String paymentRef = request.getPaymentReference();
        String rawCard = (request.getCardNumber() != null) ? request.getCardNumber().replaceAll("[\\s-]", "") : "";

        // 1. Initial check: if already processed or claimed, validate idempotency and resolve
        Optional<BankTransaction> existing = transactions.findByRequestId(requestId);
        if (existing.isPresent()) {
            BankTransaction existingTx = existing.get();
            validatePayloadMatch(existingTx, request, rawCard);

            if (!"PENDING".equals(existingTx.getStatus())) {
                log.info("Duplicate request detected for requestId={}. Returning cached final result ({}) without debiting.",
                        requestId, existingTx.getStatus());
                return toResponse(existingTx);
            }

            // Check if this pending transaction is orphaned due to a prior crash/restart
            if (isOrphaned(existingTx)) {
                log.info("Detected orphaned PENDING transaction requestId={} on retry. Finalizing recovery.", requestId);
                existingTx = recoverOrphanedTransaction(existingTx);
                return toResponse(existingTx);
            }

            if (rawCard.endsWith("9")) {
                log.info("Repeated request during delay for requestId={}. Returning PENDING without second debit.", requestId);
                return toResponse(existingTx);
            }
            // For normal cards, wait briefly for the winning thread to finalize
            return toResponse(waitForFinalResultOrPending(requestId, existingTx));
        }

        String bankRef = "BNK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        // 2. Atomically claim the request at the database level before any debit can occur
        BankTransaction claimedTx;
        try {
            claimedTx = claimService.claim(requestId, paymentRef, request.getAmount(), request.getCurrency(), bankRef, rawCard);
            log.info("Claimed requestId={} as PENDING in bank_transaction before debit.", requestId);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent race condition: another thread claimed this requestId simultaneously
            log.info("Concurrent claim conflict for requestId={}. Loading winner's transaction.", requestId);
            BankTransaction winnerTx = transactions.findByRequestId(requestId)
                    .orElseThrow(() -> ex);
            validatePayloadMatch(winnerTx, request, rawCard);

            if (rawCard.endsWith("9")) {
                return toResponse(winnerTx);
            }
            return toResponse(waitForFinalResultOrPending(requestId, winnerTx));
        }

        // 3. Simulate response delay if card ends in 9 (state is already durable and queryable in DB)
        if (rawCard.endsWith("9") && delayMs > 0) {
            log.info("Simulating {}ms delay for card ending in 9 (requestId={})", delayMs, requestId);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Delay interrupted for requestId={}", requestId);
            }
        }

        // 4. Perform authorization & debit atomically in its own database transaction
        BankTransaction finalizedTx = claimService.processDebitAndFinalize(claimedTx.getId(), rawCard, request);
        return toResponse(finalizedTx);
    }

    /**
     * Status lookup for timeout recovery.
     * Guaranteed: Finds durable pending requests during the simulated delay without 404.
     * Automatically recovers orphaned pending transactions if the original process crashed past the delay window.
     */
    public Optional<BankPaymentResponse> getStatus(String requestId) {
        Optional<BankTransaction> opt = transactions.findByRequestId(requestId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        BankTransaction tx = opt.get();
        if ("PENDING".equals(tx.getStatus()) && isOrphaned(tx)) {
            log.info("Status lookup detected orphaned PENDING transaction requestId={}. Finalizing recovery.", requestId);
            tx = recoverOrphanedTransaction(tx);
        }
        return Optional.of(toResponse(tx));
    }

    /**
     * Validates that duplicate requests for the same requestId contain identical payload parameters.
     * If amount, currency, paymentReference, or card number differs, an idempotency conflict is raised.
     * Card numbers are checked via cardHash, card entity, or rejection of unassociated ambiguous requests.
     * Full PANs and CVVs are strictly masked and never exposed in logs or exception messages.
     */
    private void validatePayloadMatch(BankTransaction existingTx, BankPaymentRequest request, String rawCard) {
        if (existingTx.getAmount() != null && request.getAmount() != null
                && existingTx.getAmount().compareTo(request.getAmount()) != 0) {
            log.warn("Idempotency conflict for requestId={}: amount mismatch (existing={}, requested={})",
                    request.getRequestId(), existingTx.getAmount(), request.getAmount());
            throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: amount does not match original request");
        }

        if (existingTx.getCurrency() != null && request.getCurrency() != null
                && !existingTx.getCurrency().equalsIgnoreCase(request.getCurrency())) {
            log.warn("Idempotency conflict for requestId={}: currency mismatch (existing={}, requested={})",
                    request.getRequestId(), existingTx.getCurrency(), request.getCurrency());
            throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: currency does not match original request");
        }

        if (existingTx.getPaymentReference() != null && request.getPaymentReference() != null
                && !existingTx.getPaymentReference().equals(request.getPaymentReference())) {
            log.warn("Idempotency conflict for requestId={}: paymentReference mismatch (existing={}, requested={})",
                    request.getRequestId(), existingTx.getPaymentReference(), request.getPaymentReference());
            throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: paymentReference does not match original request");
        }

        // Validate card number matching even when original transaction has no associated card entity
        if (existingTx.getCardHash() != null) {
            String incomingHash = BankTransactionClaimService.hashCard(rawCard);
            if (!existingTx.getCardHash().equals(incomingHash)) {
                String existingMasked = (existingTx.getMaskedCard() != null)
                        ? existingTx.getMaskedCard()
                        : maskCard(existingTx.getCard() != null ? existingTx.getCard().getCardNumber() : null);
                log.warn("Idempotency conflict for requestId={}: card mismatch (existing={}, requested={})",
                        request.getRequestId(), existingMasked, maskCard(rawCard));
                throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: card number does not match original request");
            }
        } else if (existingTx.getCard() != null) {
            if (!existingTx.getCard().getCardNumber().equals(rawCard)) {
                log.warn("Idempotency conflict for requestId={}: card mismatch (existing={}, requested={})",
                        request.getRequestId(), maskCard(existingTx.getCard().getCardNumber()), maskCard(rawCard));
                throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: card number does not match original request");
            }
        } else {
            // Legacy/edge case where existingTx has no cardHash and no card entity:
            // Never allow a new card number to be accepted blindly when original card cannot be verified
            if (rawCard != null && !rawCard.isEmpty()) {
                log.warn("Idempotency conflict for requestId={}: cannot verify card against unassociated original transaction",
                        request.getRequestId());
                throw new BankIdempotencyConflictException("Payload mismatch for duplicate requestId: cannot verify card against original unassociated request");
            }
        }
    }

    private boolean isOrphaned(BankTransaction tx) {
        if (tx.getCreatedAt() == null) {
            return false;
        }
        long thresholdMs = Math.max(delayMs + 5000, 3000);
        return Duration.between(tx.getCreatedAt(), LocalDateTime.now()).toMillis() > thresholdMs;
    }

    private BankTransaction recoverOrphanedTransaction(BankTransaction tx) {
        String cardNum = (tx.getCard() != null) ? tx.getCard().getCardNumber() : "";
        BankPaymentRequest req = new BankPaymentRequest(
                tx.getRequestId(),
                tx.getPaymentReference(),
                cardNum,
                tx.getAmount(),
                tx.getCurrency()
        );
        return claimService.processDebitAndFinalize(tx.getId(), cardNum, req);
    }

    private BankTransaction waitForFinalResultOrPending(String requestId, BankTransaction current) {
        if (!"PENDING".equals(current.getStatus())) {
            return current;
        }
        long start = System.currentTimeMillis();
        BankTransaction tx = current;
        // Wait briefly (up to 3 seconds) if the winner is currently finalizing
        while ("PENDING".equals(tx.getStatus()) && (System.currentTimeMillis() - start < 3000)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            tx = transactions.findByRequestId(requestId).orElse(tx);
        }
        return tx;
    }

    public static String maskCard(String cardNumber) {
        return BankTransactionClaimService.maskCard(cardNumber);
    }

    private BankPaymentResponse toResponse(BankTransaction tx) {
        return new BankPaymentResponse(
                tx.getRequestId(),
                tx.getPaymentReference(),
                tx.getStatus(),
                tx.getBankReference(),
                tx.getResponseCode(),
                tx.getResponseMessage()
        );
    }
}
