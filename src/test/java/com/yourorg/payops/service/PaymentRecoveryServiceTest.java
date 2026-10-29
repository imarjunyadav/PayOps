package com.yourorg.payops.service;

import com.yourorg.payops.client.bank.BankAuthResponse;
import com.yourorg.payops.client.bank.FakeBankClient;
import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.PaymentEvent;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.model.AccountType;
import com.yourorg.payops.model.JournalSource;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.PaymentMethod;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.PostingDirection;
import com.yourorg.payops.model.SwitchProtocol;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.model.SwitchType;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentEventRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.PostingRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recovery_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "payops.recovery.enabled=false" // disable automatic background timer during unit tests
})
class PaymentRecoveryServiceTest {

    @Autowired
    private PaymentRecoveryService recoveryService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository eventRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private SwitchDatabaseRepository switchDatabaseRepository;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoBean
    private FakeBankClient bankClient;

    private Merchant merchant;
    private SwitchDatabase switchDatabase;

    @BeforeEach
    void setUp() {
        postingRepository.deleteAll();
        journalEntryRepository.deleteAll();
        eventRepository.deleteAll();
        paymentRepository.deleteAll();
        accountRepository.deleteAll();
        merchantRepository.deleteAll();
        switchDatabaseRepository.deleteAll();

        // Seed required ledger accounts 1100 and 2100
        Account acc1100 = new Account();
        acc1100.setCode("1100");
        acc1100.setName("Acquirer Receivable");
        acc1100.setAccountType(AccountType.ASSET);
        acc1100.setCurrency("INR");
        acc1100.setActive(true);
        accountRepository.save(acc1100);

        Account acc2100 = new Account();
        acc2100.setCode("2100");
        acc2100.setName("Merchant Payable");
        acc2100.setAccountType(AccountType.LIABILITY);
        acc2100.setCurrency("INR");
        acc2100.setActive(true);
        accountRepository.save(acc2100);

        // Seed switch route
        switchDatabase = new SwitchDatabase();
        switchDatabase.setCode("SW-REC-1");
        switchDatabase.setName("Recovery Fake Bank");
        switchDatabase.setSwitchType(SwitchType.BANK);
        switchDatabase.setProtocol(SwitchProtocol.REST);
        switchDatabase.setStatus(SwitchStatus.ACTIVE);
        switchDatabase.setPriority(1);
        switchDatabase.setTimeoutMs(5000);
        switchDatabase = switchDatabaseRepository.save(switchDatabase);

        // Seed merchant
        merchant = new Merchant();
        merchant.setCode("M-RECOVERY");
        merchant.setName("Recovery Test Merchant");
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchant.setDefaultSwitch(switchDatabase);
        merchant = merchantRepository.save(merchant);
    }

    private Payment createPendingPayment(String bankRequestId, BigDecimal amount) {
        Payment payment = new Payment();
        payment.setReference("PAY-REC-" + UUID.randomUUID().toString().substring(0, 8));
        payment.setMerchant(merchant);
        payment.setSwitchDatabase(switchDatabase);
        payment.setAmount(amount);
        payment.setCurrency("INR");
        payment.setMethod(PaymentMethod.CARD);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setBankRequestId(bankRequestId);
        payment.setMaskedCardNumber("411111******1111");
        payment.setIdempotencyKey("KEY-" + UUID.randomUUID());
        payment.setFailureReason("Bank authorization timed out (5s limit)");
        Payment saved = paymentRepository.save(payment);

        PaymentEvent event = new PaymentEvent();
        event.setPayment(saved);
        event.setEventType(PaymentStatus.PENDING);
        event.setMessage("Timed out waiting for bank");
        eventRepository.save(event);

        return saved;
    }

    @Test
    void pendingBecomingApprovedCreatesExactlyOneBalancedJournal() {
        String bankRequestId = "BREQ-APP-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("100.00"));

        when(bankClient.getStatus(bankRequestId)).thenReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "APPROVED", "BNK-REC-APP-99", "00", "Approved")
        ));

        boolean resolved = recoveryService.recoverPendingPayment(payment.getId());
        assertTrue(resolved, "Payment must be marked resolved on approval");

        Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.APPROVED, updated.getStatus());
        assertEquals("BNK-REC-APP-99", updated.getBankReference());
        assertEquals("BNK-REC-APP-99", updated.getSwitchReference());

        // Verify PaymentEvent created for APPROVED
        List<PaymentEvent> approvedEvents = eventRepository.findByPaymentIdAndEventType(payment.getId(), PaymentStatus.APPROVED);
        assertEquals(1, approvedEvents.size());

        // Verify exactly one balanced ledger JournalEntry
        Optional<JournalEntry> journalOpt = journalEntryRepository.findByPaymentIdAndSource(payment.getId(), JournalSource.PAYMENT_APPROVED);
        assertTrue(journalOpt.isPresent());
        JournalEntry journal = journalOpt.get();

        List<Posting> postings = postingRepository.findByJournalEntryId(journal.getId());
        assertEquals(2, postings.size());

        Posting debit = postings.stream().filter(p -> p.getDirection() == PostingDirection.DEBIT).findFirst().orElseThrow();
        assertEquals("1100", debit.getAccount().getCode());
        assertEquals(0, new BigDecimal("100.00").compareTo(debit.getAmount()));

        Posting credit = postings.stream().filter(p -> p.getDirection() == PostingDirection.CREDIT).findFirst().orElseThrow();
        assertEquals("2100", credit.getAccount().getCode());
        assertEquals(0, new BigDecimal("100.00").compareTo(credit.getAmount()));
    }

    @Test
    void pendingBecomingDeclinedCreatesNoApprovalJournal() {
        String bankRequestId = "BREQ-DEC-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("50.00"));

        when(bankClient.getStatus(bankRequestId)).thenReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "DECLINED", "BNK-REC-DEC-12", "51", "Insufficient funds")
        ));

        boolean resolved = recoveryService.recoverPendingPayment(payment.getId());
        assertTrue(resolved, "Payment must be marked resolved on decline");

        Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.DECLINED, updated.getStatus());
        assertEquals("Insufficient funds", updated.getFailureReason());

        // Verify PaymentEvent created for DECLINED
        List<PaymentEvent> declinedEvents = eventRepository.findByPaymentIdAndEventType(payment.getId(), PaymentStatus.DECLINED);
        assertEquals(1, declinedEvents.size());

        // Verify strictly NO ledger journal entries are created for declines
        Optional<JournalEntry> journalOpt = journalEntryRepository.findByPaymentIdAndSource(payment.getId(), JournalSource.PAYMENT_APPROVED);
        assertTrue(journalOpt.isEmpty(), "Declined payment must not produce an approval journal entry");
        assertEquals(0, postingRepository.count(), "No postings may be created for declined payment");
    }

    @Test
    void stillPendingResponsesLeavePaymentPendingWithoutJournals() {
        String bankRequestId = "BREQ-STILL-PENDING-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("200.00"));

        when(bankClient.getStatus(bankRequestId)).thenReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "PENDING", null, "PENDING", "Authorization in progress")
        ));

        boolean resolved = recoveryService.recoverPendingPayment(payment.getId());
        assertFalse(resolved, "Payment is still pending and must not be marked resolved");

        Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, updated.getStatus());

        // Verify no APPROVED/DECLINED events and no journal entries
        assertFalse(eventRepository.existsByPaymentIdAndEventType(payment.getId(), PaymentStatus.APPROVED));
        assertFalse(eventRepository.existsByPaymentIdAndEventType(payment.getId(), PaymentStatus.DECLINED));
        assertEquals(0, journalEntryRepository.count());
    }

    @Test
    void networkFailureLeavesPaymentPendingAndRetriesSuccessfullyOnNextCycle() {
        String bankRequestId = "BREQ-NET-FAIL-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("75.00"));

        // 1. First cycle: simulate network timeout / connection refused
        when(bankClient.getStatus(bankRequestId))
                .thenThrow(new ResourceAccessException("Connection refused: connect"));

        boolean resolved = recoveryService.recoverPendingPayment(payment.getId());
        assertFalse(resolved, "Network error must not resolve or decline payment");

        Payment stillPending = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, stillPending.getStatus(), "Payment must remain PENDING on network failure");
        assertEquals(0, journalEntryRepository.count());

        // 2. Second cycle: network restored and bank returns APPROVED
        doReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "APPROVED", "BNK-REC-RETRY-01", "00", "Approved")
        )).when(bankClient).getStatus(bankRequestId);

        boolean retryResolved = recoveryService.recoverPendingPayment(payment.getId());
        assertTrue(retryResolved, "Payment must be resolved when network recovers");

        Payment approved = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.APPROVED, approved.getStatus());
        assertEquals("BNK-REC-RETRY-01", approved.getBankReference());
        assertEquals(1, journalEntryRepository.count());
    }

    @Test
    void duplicatePollingDoesNotDuplicateEventsOrJournalsOrPostings() {
        String bankRequestId = "BREQ-DUP-POLL-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("120.00"));

        when(bankClient.getStatus(bankRequestId)).thenReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "APPROVED", "BNK-REC-DUP-01", "00", "Approved")
        ));

        // First recovery poll
        boolean firstRun = recoveryService.recoverPendingPayment(payment.getId());
        assertTrue(firstRun);

        // Second duplicate recovery poll for the same payment
        boolean secondRun = recoveryService.recoverPendingPayment(payment.getId());
        assertFalse(secondRun, "Repeated poll for non-PENDING payment should be skipped");

        // Verify exactly one APPROVED event, one journal, and two postings
        List<PaymentEvent> events = eventRepository.findByPaymentIdAndEventType(payment.getId(), PaymentStatus.APPROVED);
        assertEquals(1, events.size(), "Must not duplicate PaymentEvent on repeated poll");

        assertEquals(1, journalEntryRepository.count(), "Must not duplicate JournalEntry on repeated poll");
        assertEquals(2, postingRepository.count(), "Must not duplicate Posting entries on repeated poll");
    }

    @Test
    void twoConcurrentRecoveryAttemptsForSamePaymentDebitsAndPostsExactlyOnce() throws Exception {
        String bankRequestId = "BREQ-CONCUR-REC-" + UUID.randomUUID();
        Payment payment = createPendingPayment(bankRequestId, new BigDecimal("150.00"));

        when(bankClient.getStatus(bankRequestId)).thenReturn(Optional.of(
                new BankAuthResponse(bankRequestId, payment.getReference(), "APPROVED", "BNK-REC-CONC-01", "00", "Approved")
        ));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    return recoveryService.recoverPendingPayment(payment.getId());
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Fire all concurrent recovery attempts simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All concurrent recovery attempts must complete within 10 seconds");

        int successCount = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        executor.shutdown();

        // Exactly one worker should successfully transition the payment
        assertEquals(1, successCount, "Exactly one concurrent worker must perform the transition");

        // Verify final state
        Payment finalPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(PaymentStatus.APPROVED, finalPayment.getStatus());
        assertEquals("BNK-REC-CONC-01", finalPayment.getBankReference());

        List<PaymentEvent> events = eventRepository.findByPaymentIdAndEventType(payment.getId(), PaymentStatus.APPROVED);
        assertEquals(1, events.size(), "Only 1 APPROVED event must be persisted under concurrent execution");

        assertEquals(1, journalEntryRepository.count(), "Only 1 JournalEntry must be created under concurrent execution");
        assertEquals(2, postingRepository.count(), "Only 2 postings (1 Dr, 1 Cr) must be created under concurrent execution");
    }
}
