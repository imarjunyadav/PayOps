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
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.PaymentMethod;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository payments;

    @Mock
    private MerchantRepository merchants;

    @Mock
    private SwitchDatabaseRepository switches;

    @Mock
    private PaymentEventRepository events;

    @Mock
    private FakeBankClient bankClient;

    @Mock
    private LedgerService ledgerService;

    private PaymentService paymentService;

    private Merchant activeMerchant;
    private SwitchDatabase activeSwitch;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        paymentService = new PaymentService(payments, merchants, switches, events, validator, bankClient, ledgerService);

        activeMerchant = new Merchant();
        activeMerchant.setId(1L);
        activeMerchant.setStatus(MerchantStatus.ACTIVE);

        activeSwitch = new SwitchDatabase();
        activeSwitch.setId(2L);
        activeSwitch.setStatus(SwitchStatus.ACTIVE);
        activeMerchant.setDefaultSwitch(activeSwitch);
    }

    @Test
    void rejectsInvalidLuhnCardBeforeCallingBank() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));

        Payment payment = new Payment();
        payment.setReference("PAY-LUHN-FAIL");
        payment.setMerchant(activeMerchant);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("INR");
        payment.setMethod(PaymentMethod.CARD);
        payment.setCardNumber("4111111111111112"); // Invalid check digit

        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () ->
                paymentService.processPayment("IDEM-001", payment));

        assertTrue(ex.getMessage().contains("Luhn"));
        verify(bankClient, never()).authorize(any());
        verify(payments, never()).save(any());
    }

    @Test
    void fundedCardApprovedCreatesLedgerPostings() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(2L)).thenReturn(Optional.of(activeSwitch));
        when(payments.findByMerchantIdAndIdempotencyKey(1L, "IDEM-APPROVED")).thenReturn(Optional.empty());
        when(payments.findByReference("PAY-APPROVED-01")).thenReturn(Optional.empty());

        Payment savedInitiated = new Payment();
        savedInitiated.setId(50L);
        savedInitiated.setReference("PAY-APPROVED-01");
        savedInitiated.setAmount(new BigDecimal("150.0000"));
        savedInitiated.setCurrency("INR");
        savedInitiated.setStatus(PaymentStatus.INITIATED);

        java.util.concurrent.atomic.AtomicReference<Payment> captured = new java.util.concurrent.atomic.AtomicReference<>();
        when(payments.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(50L);
            captured.set(p);
            return p;
        });
        when(payments.findById(50L)).thenAnswer(i -> Optional.ofNullable(captured.get()));

        when(bankClient.authorize(any(BankAuthRequest.class)))
                .thenReturn(new BankAuthResponse("BREQ-1", "PAY-APPROVED-01", "APPROVED", "BNK-SUCCESS-123", "00", "Approved"));

        Payment request = new Payment();
        request.setReference("PAY-APPROVED-01");
        request.setMerchant(activeMerchant);
        request.setAmount(new BigDecimal("150.0000"));
        request.setCurrency("INR");
        request.setMethod(PaymentMethod.CARD);
        request.setCardNumber("4111111111111111");

        Payment result = paymentService.processPayment("IDEM-APPROVED", request);

        assertEquals(PaymentStatus.APPROVED, result.getStatus());
        assertEquals("BNK-SUCCESS-123", result.getBankReference());
        assertEquals("411111******1111", result.getMaskedCardNumber());
        verify(bankClient, times(1)).authorize(any(BankAuthRequest.class));
        verify(ledgerService, times(1)).recordPaymentApproval(result);
    }

    @Test
    void declinedCardDoesNotCreateLedger() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(2L)).thenReturn(Optional.of(activeSwitch));
        when(payments.findByMerchantIdAndIdempotencyKey(1L, "IDEM-DECLINED")).thenReturn(Optional.empty());
        when(payments.findByReference("PAY-DECLINED-01")).thenReturn(Optional.empty());

        Payment savedInitiated = new Payment();
        savedInitiated.setId(51L);
        savedInitiated.setReference("PAY-DECLINED-01");
        savedInitiated.setAmount(new BigDecimal("5000.0000"));
        savedInitiated.setCurrency("INR");
        savedInitiated.setStatus(PaymentStatus.INITIATED);

        when(payments.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(51L);
            return p;
        });
        when(payments.findById(51L)).thenReturn(Optional.of(savedInitiated));

        when(bankClient.authorize(any(BankAuthRequest.class)))
                .thenReturn(new BankAuthResponse("BREQ-2", "PAY-DECLINED-01", "DECLINED", "BNK-DECLINED-456", "51", "Insufficient funds"));

        Payment request = new Payment();
        request.setReference("PAY-DECLINED-01");
        request.setMerchant(activeMerchant);
        request.setAmount(new BigDecimal("5000.0000"));
        request.setCurrency("INR");
        request.setMethod(PaymentMethod.CARD);
        request.setCardNumber("4000000000000002");

        Payment result = paymentService.processPayment("IDEM-DECLINED", request);

        assertEquals(PaymentStatus.DECLINED, result.getStatus());
        assertEquals("Insufficient funds", result.getFailureReason());
        verify(ledgerService, never()).recordPaymentApproval(any());
    }

    @Test
    void bankTimeoutReturnsPendingWithoutDecliningOrCreatingLedger() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));
        when(switches.findById(2L)).thenReturn(Optional.of(activeSwitch));
        when(payments.findByMerchantIdAndIdempotencyKey(1L, "IDEM-TIMEOUT")).thenReturn(Optional.empty());
        when(payments.findByReference("PAY-TIMEOUT-01")).thenReturn(Optional.empty());

        Payment savedInitiated = new Payment();
        savedInitiated.setId(52L);
        savedInitiated.setReference("PAY-TIMEOUT-01");
        savedInitiated.setAmount(new BigDecimal("100.0000"));
        savedInitiated.setCurrency("INR");
        savedInitiated.setStatus(PaymentStatus.INITIATED);

        when(payments.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            p.setId(52L);
            return p;
        });
        when(payments.findById(52L)).thenReturn(Optional.of(savedInitiated));

        when(bankClient.authorize(any(BankAuthRequest.class)))
                .thenThrow(new FakeBankClient.BankTimeoutException("Bank authorization timed out (5s limit)", null));

        Payment request = new Payment();
        request.setReference("PAY-TIMEOUT-01");
        request.setMerchant(activeMerchant);
        request.setAmount(new BigDecimal("100.0000"));
        request.setCurrency("INR");
        request.setMethod(PaymentMethod.CARD);
        request.setCardNumber("4000000000000259");

        Payment result = paymentService.processPayment("IDEM-TIMEOUT", request);

        assertEquals(PaymentStatus.PENDING, result.getStatus());
        assertTrue(result.getFailureReason().contains("timed out"));
        verify(ledgerService, never()).recordPaymentApproval(any());
    }

    @Test
    void repeatedSameIdempotencyKeyAndSamePayloadReturnsOriginalPayment() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));

        Payment existingPayment = new Payment();
        existingPayment.setId(60L);
        existingPayment.setReference("PAY-IDEM-01");
        existingPayment.setAmount(new BigDecimal("100.0000"));
        existingPayment.setCurrency("INR");
        existingPayment.setStatus(PaymentStatus.APPROVED);
        // Pre-computed hash matching the payload
        String expectedHash = com.yourorg.payops.util.RequestFingerprint.compute(
                1L, new BigDecimal("100.0000"), "INR", "CARD", "4111111111111111", "PAY-IDEM-01"
        );
        existingPayment.setRequestHash(expectedHash);
        existingPayment.setIdempotencyKey("KEY-REPEAT-1");

        when(payments.findByMerchantIdAndIdempotencyKey(1L, "KEY-REPEAT-1"))
                .thenReturn(Optional.of(existingPayment));

        Payment request = new Payment();
        request.setReference("PAY-IDEM-01");
        request.setMerchant(activeMerchant);
        request.setAmount(new BigDecimal("100.0000"));
        request.setCurrency("INR");
        request.setMethod(PaymentMethod.CARD);
        request.setCardNumber("4111111111111111");

        Payment result = paymentService.processPayment("KEY-REPEAT-1", request);

        assertEquals(existingPayment, result);
        verify(bankClient, never()).authorize(any());
        verify(payments, never()).save(any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadReturnsConflict() {
        when(merchants.findById(1L)).thenReturn(Optional.of(activeMerchant));

        Payment existingPayment = new Payment();
        existingPayment.setId(61L);
        existingPayment.setReference("PAY-IDEM-02");
        existingPayment.setAmount(new BigDecimal("100.0000"));
        existingPayment.setRequestHash("DIFFERENT_HASH_12345");
        existingPayment.setIdempotencyKey("KEY-REPEAT-2");

        when(payments.findByMerchantIdAndIdempotencyKey(1L, "KEY-REPEAT-2"))
                .thenReturn(Optional.of(existingPayment));

        Payment request = new Payment();
        request.setReference("PAY-IDEM-02");
        request.setMerchant(activeMerchant);
        request.setAmount(new BigDecimal("200.0000")); // Different amount!
        request.setCurrency("INR");
        request.setMethod(PaymentMethod.CARD);
        request.setCardNumber("4111111111111111");

        ConflictException ex = assertThrows(ConflictException.class, () ->
                paymentService.processPayment("KEY-REPEAT-2", request));

        assertTrue(ex.getMessage().contains("different request payload"));
        verify(bankClient, never()).authorize(any());
    }
}
