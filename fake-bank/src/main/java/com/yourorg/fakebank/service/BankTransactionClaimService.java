package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.entity.BankTransaction;
import com.yourorg.fakebank.repository.BankCardRepository;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class BankTransactionClaimService {
    private static final Logger log = LoggerFactory.getLogger(BankTransactionClaimService.class);

    private final BankCardRepository cards;
    private final BankTransactionRepository transactions;

    public BankTransactionClaimService(BankCardRepository cards, BankTransactionRepository transactions) {
        this.cards = cards;
        this.transactions = transactions;
    }

    /**
     * Atomically claims the request by inserting a PENDING record into bank_transaction.
     * Enforced by the uq_bank_transaction_request_id unique constraint.
     * Stores durable card fingerprint (cardHash and maskedCard) even if the card is not in bank_card table.
     * Guaranteed: only ONE thread can claim a given requestId before any debit occurs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransaction claim(String requestId, String paymentReference, BigDecimal amount, String currency, String bankRef, String rawCard) {
        BankTransaction tx = new BankTransaction();
        tx.setRequestId(requestId);
        tx.setPaymentReference(paymentReference);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setStatus("PENDING");
        tx.setBankReference(bankRef);
        tx.setResponseCode("PENDING");
        tx.setResponseMessage("Authorization in progress");
        tx.setDebited(false);

        if (rawCard != null && !rawCard.isEmpty()) {
            tx.setCardHash(hashCard(rawCard));
            tx.setMaskedCard(maskCard(rawCard));
            cards.findByCardNumber(rawCard).ifPresent(tx::setCard);
        }

        return transactions.saveAndFlush(tx);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransaction claim(String requestId, String paymentReference, BigDecimal amount, String currency, String bankRef) {
        return claim(requestId, paymentReference, amount, currency, bankRef, null);
    }

    /**
     * Finalizes the transaction and debits the card balance atomically.
     * Runs in its own transaction (REQUIRES_NEW).
     * Locks the BankTransaction row exclusively with PESSIMISTIC_WRITE lock before checking status.
     * If already finalized by another thread/recovery, returns immediately without debiting.
     * Strictly preserves lock hierarchy (BankTransaction -> BankCard) to eliminate deadlock risk.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransaction processDebitAndFinalize(Long transactionId, String rawCard, BankPaymentRequest request) {
        // 1. Lock the BankTransaction row exclusively
        BankTransaction tx = transactions.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Claimed transaction not found: " + transactionId));

        // 2. Recheck status under exclusive lock: if already finalized or debited, return immediately
        if (!"PENDING".equals(tx.getStatus()) || tx.isDebited()) {
            log.info("Transaction id={} was already finalized with status={} (debited={}). Skipping second debit.",
                    transactionId, tx.getStatus(), tx.isDebited());
            return tx;
        }

        // 3. Lock card row exclusively
        Optional<BankCard> cardOpt = (rawCard != null && !rawCard.isEmpty())
                ? cards.findByCardNumberForUpdate(rawCard)
                : Optional.empty();

        if (cardOpt.isEmpty()) {
            log.warn("Card not found for requestId={}", request.getRequestId());
            tx.setStatus("DECLINED");
            tx.setResponseCode("14");
            tx.setResponseMessage("Invalid or unknown card");
            tx.setDebited(false);
            return transactions.saveAndFlush(tx);
        }

        BankCard card = cardOpt.get();
        tx.setCard(card);

        if (!"ACTIVE".equalsIgnoreCase(card.getStatus())) {
            log.warn("Card inactive/blocked for requestId={}", request.getRequestId());
            tx.setStatus("DECLINED");
            tx.setResponseCode("05");
            tx.setResponseMessage("Card is not active: " + card.getStatus());
            tx.setDebited(false);
            return transactions.saveAndFlush(tx);
        }

        if (card.getBalance().compareTo(request.getAmount()) < 0) {
            log.info("Insufficient funds on card (balance={}, required={}) for requestId={}",
                    card.getBalance(), request.getAmount(), request.getRequestId());
            tx.setStatus("DECLINED");
            tx.setResponseCode("51");
            tx.setResponseMessage("Insufficient funds");
            tx.setDebited(false);
            return transactions.saveAndFlush(tx);
        }

        // Debit card balance exactly once
        card.setBalance(card.getBalance().subtract(request.getAmount()));
        cards.save(card);

        tx.setStatus("APPROVED");
        tx.setResponseCode("00");
        tx.setResponseMessage("Approved");
        tx.setDebited(true);

        log.info("Card debited successfully: amount={}, new balance={}, requestId={}, bankRef={}",
                request.getAmount(), card.getBalance(), request.getRequestId(), tx.getBankReference());

        return transactions.saveAndFlush(tx);
    }

    public static String hashCard(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cardNumber.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static String maskCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        String last4 = cardNumber.substring(cardNumber.length() - 4);
        int maskedPrefixLen = cardNumber.length() - 4;
        return "*".repeat(maskedPrefixLen) + last4;
    }
}
