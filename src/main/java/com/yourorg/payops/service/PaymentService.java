package com.yourorg.payops.service;

import com.yourorg.payops.client.bank.BankAuthRequest;
import com.yourorg.payops.client.bank.BankAuthResponse;
import com.yourorg.payops.client.bank.FakeBankClient;
import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.PaymentMethod;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import com.yourorg.payops.util.CardMasker;
import com.yourorg.payops.util.LuhnValidator;
import com.yourorg.payops.util.RequestFingerprint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final MerchantRepository merchants;
    private final SwitchDatabaseRepository switches;
    private final PaymentEventRepository events;
    private final Validator validator;
    private final FakeBankClient bankClient;
    private final LedgerService ledgerService;

    public PaymentService(PaymentRepository payments,
                          MerchantRepository merchants,
                          SwitchDatabaseRepository switches,
                          PaymentEventRepository events,
                          Validator validator,
                          FakeBankClient bankClient,
                          LedgerService ledgerService) {
        this.payments = payments;
        this.merchants = merchants;
        this.switches = switches;
        this.events = events;
        this.validator = validator;
        this.bankClient = bankClient;
        this.ledgerService = ledgerService;
    }

    /**
     * Orchestrates payment creation, idempotency check, Luhn validation, fake-bank authorization,
     * timeout handling, and atomic double-entry ledger posting.
     */
    public Payment processPayment(String idempotencyKey, Payment body) {
        if (body.getMerchant() == null || body.getMerchant().getId() == null) {
            reject("merchant", "must identify an existing merchant");
        }
        Merchant merchant = merchants.findById(body.getMerchant().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", body.getMerchant().getId()));
        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new BusinessRuleException("Payments can only be initiated for active merchants");
        }

        // Determine effective idempotency key
        String effectiveKey = idempotencyKey;
        if (effectiveKey == null || effectiveKey.isBlank()) {
            effectiveKey = body.getIdempotencyKey();
        }

        // Require idempotency key for card payments as required by Phase 1 specification
        if (body.getMethod() == PaymentMethod.CARD && (effectiveKey == null || effectiveKey.isBlank())) {
            throw new BusinessRuleException("Idempotency key is required for card payment submissions (provide 'Idempotency-Key' header)");
        }

        String rawCardNumber = body.getCardNumber();
        String requestHash = null;
        if (effectiveKey != null && !effectiveKey.isBlank()) {
            requestHash = RequestFingerprint.compute(
                    merchant.getId(),
                    body.getAmount(),
                    body.getCurrency(),
                    body.getMethod() != null ? body.getMethod().name() : null,
                    rawCardNumber,
                    body.getReference()
            );

            // 1. Idempotency check against existing records
            Optional<Payment> existingOpt = payments.findByMerchantIdAndIdempotencyKey(merchant.getId(), effectiveKey);
            if (existingOpt.isPresent()) {
                Payment existing = existingOpt.get();
                if (requestHash.equals(existing.getRequestHash())) {
                    log.info("Idempotent replay for merchant {} with key '{}'", merchant.getId(), effectiveKey);
                    return existing;
                } else {
                    log.warn("Idempotency conflict for merchant {} with key '{}'", merchant.getId(), effectiveKey);
                    throw new ConflictException("Idempotency key '" + effectiveKey + "' was already used with a different request payload");
                }
            }
        }

        // 2. Validate card format and Luhn checksum before contacting fake bank
        if (body.getMethod() == PaymentMethod.CARD) {
            if (rawCardNumber == null || rawCardNumber.isBlank()) {
                reject("cardNumber", "Card number is required for CARD payments");
            }
            if (!LuhnValidator.isValid(rawCardNumber)) {
                log.warn("Card number failed Luhn validation for ref={}", body.getReference());
                throw new BusinessRuleException("Invalid card number: failed Luhn checksum validation");
            }
        }

        if (body.getStatus() != null && body.getStatus() != PaymentStatus.INITIATED) {
            throw new BusinessRuleException("New payments must start in INITIATED status");
        }
        if (body.getRefundedAmount() != null && body.getRefundedAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessRuleException("A new payment cannot have a refunded amount");
        }

        SwitchDatabase route = resolveActiveRoute(merchant);
        body.setMerchant(merchant);
        body.setSwitchDatabase(route);
        body.setId(null);
        body.setStatus(PaymentStatus.INITIATED);
        body.setRefundedAmount(BigDecimal.ZERO);
        body.setSwitchReference(null);
        body.setFailureReason(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        body.setIdempotencyKey(effectiveKey);
        body.setRequestHash(requestHash);

        if (rawCardNumber != null && !rawCardNumber.isBlank()) {
            body.setMaskedCardNumber(CardMasker.mask(rawCardNumber));
        }

        // Generate stable bank request ID before calling the bank
        String bankRequestId = "BREQ-" + UUID.randomUUID().toString();
        body.setBankRequestId(bankRequestId);

        validate(body);

        if (payments.findByReference(body.getReference()).isPresent()) {
            throw new ConflictException("Payment reference already exists");
        }

        // 3. Persist initial payment record in a separate transaction so bankRequestId is durable
        // and database lock is NOT held open during remote bank HTTP call.
        Payment saved;
        try {
            saved = saveInitialPayment(body);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate submission race condition handling
            if (effectiveKey != null && !effectiveKey.isBlank()) {
                Optional<Payment> concurrent = payments.findByMerchantIdAndIdempotencyKey(merchant.getId(), effectiveKey);
                if (concurrent.isEmpty() && body.getReference() != null) {
                    concurrent = payments.findByReference(body.getReference());
                }
                if (concurrent.isPresent()) {
                    Payment winner = concurrent.get();
                    if (requestHash == null || requestHash.equals(winner.getRequestHash())) {
                        // If winner is still in-flight, wait briefly for it to complete authorization
                        long startWait = System.currentTimeMillis();
                        while (winner.getStatus() == PaymentStatus.INITIATED && (System.currentTimeMillis() - startWait < 6000)) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            winner = payments.findById(winner.getId()).orElse(winner);
                        }
                        return winner;
                    } else {
                        throw new ConflictException("Idempotency key '" + effectiveKey + "' was already used with a different request payload");
                    }
                }
            }
            throw new ConflictException("Concurrent conflict or duplicate payment submission");
        }

        // Non-card payments (e.g. UPI) remain in INITIATED status
        if (body.getMethod() != PaymentMethod.CARD || bankClient == null) {
            return saved;
        }

        // 4. Call Fake Bank over HTTP (with 5-second timeout, outside DB transaction)
        BankAuthRequest bankRequest = new BankAuthRequest(
                bankRequestId,
                saved.getReference(),
                rawCardNumber,
                saved.getAmount(),
                saved.getCurrency()
        );

        try {
            BankAuthResponse bankResponse = bankClient.authorize(bankRequest);
            if (bankResponse != null && "APPROVED".equalsIgnoreCase(bankResponse.getStatus())) {
                return finalizePaymentApproval(saved.getId(), bankResponse.getBankReference());
            } else {
                String reason = (bankResponse != null) ? bankResponse.getResponseMessage() : "Declined by bank";
                String bankRef = (bankResponse != null) ? bankResponse.getBankReference() : null;
                return finalizePaymentDecline(saved.getId(), bankRef, reason);
            }
        } catch (FakeBankClient.BankTimeoutException e) {
            log.warn("Bank call timed out for payment id={}. Marking PENDING for timeout recovery.", saved.getId());
            return markPaymentPending(saved.getId(), "Bank authorization timed out (5s limit)");
        } catch (Exception e) {
            log.error("Bank call failed for payment id={}. Marking PENDING.", saved.getId(), e);
            return markPaymentPending(saved.getId(), "Bank authorization uncertain: " + e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment saveInitialPayment(Payment payment) {
        Payment saved = payments.save(payment);
        PaymentEvent initialEvent = new PaymentEvent();
        initialEvent.setPayment(saved);
        initialEvent.setEventType(PaymentStatus.INITIATED);
        initialEvent.setMessage("Payment initiated");
        events.save(initialEvent);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment finalizePaymentApproval(Long paymentId, String bankReference) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        payment.setStatus(PaymentStatus.APPROVED);
        payment.setBankReference(bankReference);
        payment.setSwitchReference(bankReference);
        Payment updated = payments.save(payment);

        PaymentEvent approvedEvent = new PaymentEvent();
        approvedEvent.setPayment(updated);
        approvedEvent.setEventType(PaymentStatus.APPROVED);
        approvedEvent.setMessage("Payment approved by bank (ref: " + bankReference + ")");
        events.save(approvedEvent);

        // Automated double-entry ledger posting
        ledgerService.recordPaymentApproval(updated);

        return updated;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment finalizePaymentDecline(Long paymentId, String bankReference, String reason) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        payment.setStatus(PaymentStatus.DECLINED);
        payment.setBankReference(bankReference);
        payment.setFailureReason(reason);
        Payment updated = payments.save(payment);

        PaymentEvent declinedEvent = new PaymentEvent();
        declinedEvent.setPayment(updated);
        declinedEvent.setEventType(PaymentStatus.DECLINED);
        declinedEvent.setMessage("Payment declined by bank: " + reason);
        events.save(declinedEvent);

        return updated;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment markPaymentPending(Long paymentId, String reason) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        payment.setStatus(PaymentStatus.PENDING);
        payment.setFailureReason(reason);
        Payment updated = payments.save(payment);

        PaymentEvent pendingEvent = new PaymentEvent();
        pendingEvent.setPayment(updated);
        pendingEvent.setEventType(PaymentStatus.PENDING);
        pendingEvent.setMessage(reason);
        events.save(pendingEvent);

        return updated;
    }

    private SwitchDatabase resolveActiveRoute(Merchant merchant) {
        SwitchDatabase preferred = merchant.getDefaultSwitch();
        if (preferred != null && preferred.getId() != null) {
            Optional<SwitchDatabase> swOpt = switches.findById(preferred.getId());
            if (swOpt.isPresent() && swOpt.get().getStatus() == SwitchStatus.ACTIVE) {
                return swOpt.get();
            }
        }
        return switches.findByStatusOrderByPriorityAsc(SwitchStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("No active switch route is available"));
    }

    private void validate(Payment payment) {
        Set<ConstraintViolation<Payment>> violations = validator.validate(payment);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void reject(String property, String message) {
        Set<ConstraintViolation<Payment>> violations = validator.validateValue(Payment.class, property, null);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        throw new BusinessRuleException(property + " " + message);
    }
}
