package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.JournalSource;
import com.yourorg.payops.model.PostingDirection;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.PostingRepository;
import com.yourorg.payops.repository.SettlementRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JournalEntryControllerTest {
    private JournalEntryRepository journals;
    private PostingRepository postings;
    private AccountRepository accounts;
    private JournalEntryController controller;
    private JournalEntry journal;

    @BeforeEach
    void setUp() {
        journals = mock(JournalEntryRepository.class);
        postings = mock(PostingRepository.class);
        accounts = mock(AccountRepository.class);
        controller = new JournalEntryController(journals, postings, accounts,
                mock(PaymentRepository.class), mock(SettlementRecordRepository.class));
        journal = new JournalEntry();
        journal.setId(12L);
        journal.setEntryNumber("manual-12");
        journal.setSource(JournalSource.MANUAL);
        when(journals.findById(12L)).thenReturn(Optional.of(journal));
        when(accounts.findById(1L)).thenReturn(Optional.of(account(1L)));
        when(accounts.findById(2L)).thenReturn(Optional.of(account(2L)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/journal-entries");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsJournalHeader() {
        when(journals.existsByEntryNumber("new-entry")).thenReturn(false);
        when(journals.save(any(JournalEntry.class))).thenAnswer(call -> {
            JournalEntry saved = call.getArgument(0);
            saved.setId(19L);
            return saved;
        });
        JournalEntry request = new JournalEntry();
        request.setEntryNumber("new-entry");
        request.setSource(JournalSource.MANUAL);

        var response = controller.create(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(19L, response.getBody().getId());
    }

    @Test
    void rejectsDuplicateJournalNumber() {
        JournalEntry request = new JournalEntry();
        request.setEntryNumber("manual-12");
        when(journals.existsByEntryNumber("manual-12")).thenReturn(true);

        assertThrows(ConflictException.class, () -> controller.create(request));
        verify(journals, never()).save(any());
    }

    @Test
    void rejectsPostingBatchForMissingJournal() {
        when(journals.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> controller.addPostings(404L, balancedBatch()));
    }

    @Test
    void rejectsPostingForMissingAccount() {
        when(accounts.findById(99L)).thenReturn(Optional.empty());
        List<Posting> batch = balancedBatch();
        batch.getFirst().setAccount(account(99L));

        assertThrows(ResourceNotFoundException.class, () -> controller.addPostings(12L, batch));
        verify(postings, never()).saveAll(any());
    }

    @Test
    void rejectsZeroPostingAmount() {
        List<Posting> batch = balancedBatch();
        batch.getFirst().setAmount(BigDecimal.ZERO);
        assertThrows(BusinessRuleException.class, () -> controller.addPostings(12L, batch));
        verify(postings, never()).saveAll(any());
    }

    @Test
    void rejectsNegativePostingAmount() {
        List<Posting> batch = balancedBatch();
        batch.getFirst().setAmount(new BigDecimal("-1"));
        assertThrows(BusinessRuleException.class, () -> controller.addPostings(12L, batch));
        verify(postings, never()).saveAll(any());
    }

    @Test
    void savesBalancedDebitAndCreditLinesAsOneBatch() {
        when(postings.saveAll(any(Iterable.class))).thenAnswer(call -> call.getArgument(0));
        List<Posting> batch = balancedBatch();

        var response = controller.addPostings(12L, batch);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(journal, batch.getFirst().getJournalEntry());
        assertEquals(new BigDecimal("10.0000"), batch.getFirst().getAmount());
        verify(postings).saveAll(batch);
    }

    @Test
    void rejectsUnbalancedBatchBeforeSaving() {
        List<Posting> batch = balancedBatch();
        batch.getLast().setAmount(new BigDecimal("9.99"));
        assertThrows(BusinessRuleException.class, () -> controller.addPostings(12L, batch));
        verify(postings, never()).saveAll(any());
    }

    @Test
    void postingBatchWriteIsTransactional() throws Exception {
        var method = JournalEntryController.class.getMethod("addPostings", Long.class, List.class);
        org.junit.jupiter.api.Assertions.assertNotNull(method.getAnnotation(Transactional.class));
    }

    private List<Posting> balancedBatch() {
        Posting debit = new Posting();
        debit.setAccount(account(1L));
        debit.setDirection(PostingDirection.DEBIT);
        debit.setAmount(new BigDecimal("10.0000"));
        Posting credit = new Posting();
        credit.setAccount(account(2L));
        credit.setDirection(PostingDirection.CREDIT);
        credit.setAmount(new BigDecimal("10.00"));
        return new java.util.ArrayList<>(List.of(debit, credit));
    }

    private Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        return account;
    }
}
