package com.yourorg.payops.service;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.model.JournalSource;
import com.yourorg.payops.model.PostingDirection;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.PostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private JournalEntryRepository journalEntries;

    @Mock
    private PostingRepository postings;

    @Mock
    private AccountRepository accounts;

    private LedgerService ledgerService;

    private Account acquirerReceivable;
    private Account merchantPayable;
    private Payment payment;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(journalEntries, postings, accounts);

        acquirerReceivable = new Account();
        acquirerReceivable.setId(1L);
        acquirerReceivable.setCode("1100");
        acquirerReceivable.setName("Acquirer Receivable");

        merchantPayable = new Account();
        merchantPayable.setId(2L);
        merchantPayable.setCode("2100");
        merchantPayable.setName("Merchant Payable");

        payment = new Payment();
        payment.setId(10L);
        payment.setReference("PAY-LEDGER-01");
        payment.setAmount(new BigDecimal("250.0000"));
    }

    @Test
    void createsTwoBalancedPostingsForApprovedPayment() {
        when(journalEntries.findByPaymentIdAndSource(10L, JournalSource.PAYMENT_APPROVED)).thenReturn(Optional.empty());
        when(accounts.findByCode("1100")).thenReturn(Optional.of(acquirerReceivable));
        when(accounts.findByCode("2100")).thenReturn(Optional.of(merchantPayable));
        when(journalEntries.save(any(JournalEntry.class))).thenAnswer(i -> {
            JournalEntry je = i.getArgument(0);
            je.setId(100L);
            return je;
        });

        JournalEntry result = ledgerService.recordPaymentApproval(payment);

        assertNotNull(result);
        assertEquals(JournalSource.PAYMENT_APPROVED, result.getSource());
        assertEquals(payment, result.getPayment());

        ArgumentCaptor<Posting> postingCaptor = ArgumentCaptor.forClass(Posting.class);
        verify(postings, times(2)).save(postingCaptor.capture());

        List<Posting> savedPostings = postingCaptor.getAllValues();
        Posting debit = savedPostings.stream().filter(p -> p.getDirection() == PostingDirection.DEBIT).findFirst().orElseThrow();
        Posting credit = savedPostings.stream().filter(p -> p.getDirection() == PostingDirection.CREDIT).findFirst().orElseThrow();

        assertEquals(acquirerReceivable, debit.getAccount());
        assertEquals(new BigDecimal("250.0000"), debit.getAmount());

        assertEquals(merchantPayable, credit.getAccount());
        assertEquals(new BigDecimal("250.0000"), credit.getAmount());

        // Debits equal credits
        assertEquals(debit.getAmount(), credit.getAmount());
    }

    @Test
    void doesNotDuplicateLedgerPostingsWhenRetried() {
        JournalEntry existing = new JournalEntry();
        existing.setId(99L);
        existing.setEntryNumber("JE-APP-PAY-LEDGER-01");
        existing.setSource(JournalSource.PAYMENT_APPROVED);

        when(journalEntries.findByPaymentIdAndSource(10L, JournalSource.PAYMENT_APPROVED)).thenReturn(Optional.of(existing));

        JournalEntry result = ledgerService.recordPaymentApproval(payment);

        assertEquals(existing, result);
        verify(journalEntries, never()).save(any());
        verify(postings, never()).save(any());
    }
}
