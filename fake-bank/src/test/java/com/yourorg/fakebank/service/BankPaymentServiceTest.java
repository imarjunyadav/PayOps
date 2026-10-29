package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.entity.BankTransaction;
import com.yourorg.fakebank.exception.BankIdempotencyConflictException;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BankPaymentServiceTest {

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankTransactionClaimService claimService;

    private BankPaymentService service;

    @BeforeEach
    void setUp() {
        service = new BankPaymentService(transactions, claimService, 0); // 0ms delay for unit test
    }

    @Test
    void fundedCardApprovalAndExactlyOneDebit() {
        when(transactions.findByRequestId("REQ-001")).thenReturn(Optional.empty());

        BankTransaction claimedTx = new BankTransaction();
        claimedTx.setId(1L);
        claimedTx.setRequestId("REQ-001");
        claimedTx.setStatus("PENDING");
        when(claimService.claim(eq("REQ-001"), eq("PAY-001"), eq(new BigDecimal("100.00")), eq("INR"), anyString(), eq("4111111111111111")))
                .thenReturn(claimedTx);

        BankTransaction finalizedTx = new BankTransaction();
        finalizedTx.setId(1L);
        finalizedTx.setRequestId("REQ-001");
        finalizedTx.setPaymentReference("PAY-001");
        finalizedTx.setStatus("APPROVED");
        finalizedTx.setBankReference("BNK-APPROVED-1");
        finalizedTx.setResponseCode("00");
        finalizedTx.setResponseMessage("Approved");
        finalizedTx.setDebited(true);
        when(claimService.processDebitAndFinalize(eq(1L), anyString(), any(BankPaymentRequest.class)))
                .thenReturn(finalizedTx);

        BankPaymentRequest req = new BankPaymentRequest("REQ-001", "PAY-001", "4111111111111111", new BigDecimal("100.00"), "INR");
        BankPaymentResponse resp = service.authorize(req);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals("00", resp.getResponseCode());
        assertEquals("BNK-APPROVED-1", resp.getBankReference());
        verify(claimService, times(1)).claim(any(), any(), any(), any(), any(), any());
        verify(claimService, times(1)).processDebitAndFinalize(eq(1L), anyString(), any());
    }

    @Test
    void insufficientFundsDeclinesWithoutDebit() {
        when(transactions.findByRequestId("REQ-002")).thenReturn(Optional.empty());

        BankTransaction claimedTx = new BankTransaction();
        claimedTx.setId(2L);
        claimedTx.setRequestId("REQ-002");
        claimedTx.setStatus("PENDING");
        when(claimService.claim(eq("REQ-002"), eq("PAY-002"), eq(new BigDecimal("50.00")), eq("INR"), anyString(), eq("4000000000000002")))
                .thenReturn(claimedTx);

        BankTransaction declinedTx = new BankTransaction();
        declinedTx.setId(2L);
        declinedTx.setRequestId("REQ-002");
        declinedTx.setPaymentReference("PAY-002");
        declinedTx.setStatus("DECLINED");
        declinedTx.setBankReference("BNK-DECLINED-1");
        declinedTx.setResponseCode("51");
        declinedTx.setResponseMessage("Insufficient funds");
        declinedTx.setDebited(false);
        when(claimService.processDebitAndFinalize(eq(2L), anyString(), any(BankPaymentRequest.class)))
                .thenReturn(declinedTx);

        BankPaymentRequest req = new BankPaymentRequest("REQ-002", "PAY-002", "4000000000000002", new BigDecimal("50.00"), "INR");
        BankPaymentResponse resp = service.authorize(req);

        assertEquals("DECLINED", resp.getStatus());
        assertEquals("51", resp.getResponseCode());
        assertEquals("Insufficient funds", resp.getResponseMessage());
        verify(claimService, times(1)).claim(any(), any(), any(), any(), any(), any());
        verify(claimService, times(1)).processDebitAndFinalize(eq(2L), anyString(), any());
    }

    @Test
    void unknownCardDeclinesWithoutDebit() {
        when(transactions.findByRequestId("REQ-003")).thenReturn(Optional.empty());

        BankTransaction claimedTx = new BankTransaction();
        claimedTx.setId(3L);
        claimedTx.setRequestId("REQ-003");
        claimedTx.setStatus("PENDING");
        when(claimService.claim(eq("REQ-003"), eq("PAY-003"), eq(new BigDecimal("100.00")), eq("INR"), anyString(), eq("4999999999999999")))
                .thenReturn(claimedTx);

        BankTransaction declinedTx = new BankTransaction();
        declinedTx.setId(3L);
        declinedTx.setRequestId("REQ-003");
        declinedTx.setPaymentReference("PAY-003");
        declinedTx.setStatus("DECLINED");
        declinedTx.setBankReference("BNK-DECLINED-2");
        declinedTx.setResponseCode("14");
        declinedTx.setResponseMessage("Invalid or unknown card");
        declinedTx.setDebited(false);
        when(claimService.processDebitAndFinalize(eq(3L), anyString(), any(BankPaymentRequest.class)))
                .thenReturn(declinedTx);

        BankPaymentRequest req = new BankPaymentRequest("REQ-003", "PAY-003", "4999999999999999", new BigDecimal("100.00"), "INR");
        BankPaymentResponse resp = service.authorize(req);

        assertEquals("DECLINED", resp.getStatus());
        assertEquals("14", resp.getResponseCode());
        verify(claimService, times(1)).claim(any(), any(), any(), any(), any(), any());
        verify(claimService, times(1)).processDebitAndFinalize(eq(3L), anyString(), any());
    }

    @Test
    void duplicateBankRequestWithIdenticalPayloadReturnsCachedResult() {
        BankCard card = new BankCard("4111111111111111", "Merchant", 12, 2028, "123", new BigDecimal("50000"), "INR", "ACTIVE");
        BankTransaction existingTx = new BankTransaction();
        existingTx.setRequestId("REQ-DUP-1");
        existingTx.setPaymentReference("PAY-DUP-1");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setCurrency("INR");
        existingTx.setCard(card);
        existingTx.setCardHash(BankTransactionClaimService.hashCard("4111111111111111"));
        existingTx.setMaskedCard("************1111");
        existingTx.setStatus("APPROVED");
        existingTx.setBankReference("BNK-EXISTING-123");
        existingTx.setResponseCode("00");
        existingTx.setResponseMessage("Approved");
        existingTx.setDebited(true);

        when(transactions.findByRequestId("REQ-DUP-1")).thenReturn(Optional.of(existingTx));

        BankPaymentRequest req = new BankPaymentRequest("REQ-DUP-1", "PAY-DUP-1", "4111111111111111", new BigDecimal("100.00"), "INR");
        BankPaymentResponse resp = service.authorize(req);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals("BNK-EXISTING-123", resp.getBankReference());
        verify(claimService, never()).claim(any(), any(), any(), any(), any(), any());
        verify(claimService, never()).processDebitAndFinalize(any(), any(), any());
    }

    @Test
    void duplicateRequestIdWithDifferentAmountThrowsConflictException() {
        BankTransaction existingTx = new BankTransaction();
        existingTx.setRequestId("REQ-DUP-CONFLICT");
        existingTx.setPaymentReference("PAY-DUP-1");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setCurrency("INR");
        existingTx.setStatus("APPROVED");

        when(transactions.findByRequestId("REQ-DUP-CONFLICT")).thenReturn(Optional.of(existingTx));

        // Attempt reuse with different amount (200.00 instead of 100.00)
        BankPaymentRequest req = new BankPaymentRequest("REQ-DUP-CONFLICT", "PAY-DUP-1", "4111111111111111", new BigDecimal("200.00"), "INR");

        BankIdempotencyConflictException ex = assertThrows(BankIdempotencyConflictException.class, () -> service.authorize(req));
        assertTrue(ex.getMessage().contains("amount does not match"));
        verify(claimService, never()).processDebitAndFinalize(any(), any(), any());
    }

    @Test
    void duplicateRequestIdWithDifferentCardThrowsConflictExceptionWithoutExposingCard() {
        BankCard originalCard = new BankCard("4111111111111111", "Merchant", 12, 2028, "123", new BigDecimal("50000"), "INR", "ACTIVE");
        BankTransaction existingTx = new BankTransaction();
        existingTx.setRequestId("REQ-DUP-CARD");
        existingTx.setPaymentReference("PAY-DUP-1");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setCurrency("INR");
        existingTx.setCard(originalCard);
        existingTx.setCardHash(BankTransactionClaimService.hashCard("4111111111111111"));
        existingTx.setMaskedCard("************1111");
        existingTx.setStatus("APPROVED");

        when(transactions.findByRequestId("REQ-DUP-CARD")).thenReturn(Optional.of(existingTx));

        // Attempt reuse with different card number
        BankPaymentRequest req = new BankPaymentRequest("REQ-DUP-CARD", "PAY-DUP-1", "4000000000000002", new BigDecimal("100.00"), "INR");

        BankIdempotencyConflictException ex = assertThrows(BankIdempotencyConflictException.class, () -> service.authorize(req));
        assertTrue(ex.getMessage().contains("card number does not match"));
        assertFalse(ex.getMessage().contains("4111111111111111"), "Must not expose full card number in exception message");
        assertFalse(ex.getMessage().contains("4000000000000002"), "Must not expose full card number in exception message");
    }

    @Test
    void duplicateRequestIdWithDifferentCardWhenOriginalCardWasNullThrowsConflictException() {
        // Original transaction had no associated card entity (e.g., unknown card decline)
        BankTransaction existingTx = new BankTransaction();
        existingTx.setRequestId("REQ-DUP-NULL-CARD");
        existingTx.setPaymentReference("PAY-UNK-1");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setCurrency("INR");
        existingTx.setCard(null);
        existingTx.setCardHash(BankTransactionClaimService.hashCard("4999999999999999"));
        existingTx.setMaskedCard("************9999");
        existingTx.setStatus("DECLINED");
        existingTx.setResponseCode("14");
        existingTx.setResponseMessage("Invalid or unknown card");

        when(transactions.findByRequestId("REQ-DUP-NULL-CARD")).thenReturn(Optional.of(existingTx));

        // Attempt duplicate request with different card number ("4111111111111111")
        BankPaymentRequest req = new BankPaymentRequest("REQ-DUP-NULL-CARD", "PAY-UNK-1", "4111111111111111", new BigDecimal("100.00"), "INR");

        BankIdempotencyConflictException ex = assertThrows(BankIdempotencyConflictException.class, () -> service.authorize(req));
        assertTrue(ex.getMessage().contains("card number does not match"));
        assertFalse(ex.getMessage().contains("4999999999999999"), "Must not expose original PAN in exception message");
        assertFalse(ex.getMessage().contains("4111111111111111"), "Must not expose requested PAN in exception message");
    }

    @Test
    void duplicateRequestIdWithSameUnknownCardWhenOriginalCardWasNullReturnsCachedDecline() {
        BankTransaction existingTx = new BankTransaction();
        existingTx.setRequestId("REQ-DUP-SAME-UNK");
        existingTx.setPaymentReference("PAY-UNK-2");
        existingTx.setAmount(new BigDecimal("100.00"));
        existingTx.setCurrency("INR");
        existingTx.setCard(null);
        existingTx.setCardHash(BankTransactionClaimService.hashCard("4999999999999999"));
        existingTx.setMaskedCard("************9999");
        existingTx.setStatus("DECLINED");
        existingTx.setResponseCode("14");
        existingTx.setResponseMessage("Invalid or unknown card");
        existingTx.setBankReference("BNK-DEC-UNK");

        when(transactions.findByRequestId("REQ-DUP-SAME-UNK")).thenReturn(Optional.of(existingTx));

        // Submit duplicate with the exact same unknown card
        BankPaymentRequest req = new BankPaymentRequest("REQ-DUP-SAME-UNK", "PAY-UNK-2", "4999999999999999", new BigDecimal("100.00"), "INR");
        BankPaymentResponse resp = service.authorize(req);

        assertEquals("DECLINED", resp.getStatus());
        assertEquals("14", resp.getResponseCode());
        assertEquals("BNK-DEC-UNK", resp.getBankReference());
        verify(claimService, never()).claim(any(), any(), any(), any(), any(), any());
        verify(claimService, never()).processDebitAndFinalize(any(), any(), any());
    }

    @Test
    void statusLookupRetrievesPersistedBankResult() {
        BankTransaction tx = new BankTransaction();
        tx.setRequestId("REQ-RECOVER-1");
        tx.setPaymentReference("PAY-RECOVER-1");
        tx.setStatus("APPROVED");
        tx.setBankReference("BNK-REC-999");
        tx.setResponseCode("00");
        tx.setResponseMessage("Approved");

        when(transactions.findByRequestId("REQ-RECOVER-1")).thenReturn(Optional.of(tx));

        Optional<BankPaymentResponse> opt = service.getStatus("REQ-RECOVER-1");
        assertTrue(opt.isPresent());
        assertEquals("APPROVED", opt.get().getStatus());
        assertEquals("BNK-REC-999", opt.get().getBankReference());
    }
}
