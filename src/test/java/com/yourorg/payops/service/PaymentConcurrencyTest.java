package com.yourorg.payops.service;

import com.yourorg.payops.client.bank.BankAuthRequest;
import com.yourorg.payops.client.bank.BankAuthResponse;
import com.yourorg.payops.client.bank.FakeBankClient;
import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.Merchant;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.model.AccountType;
import com.yourorg.payops.model.MerchantStatus;
import com.yourorg.payops.model.PaymentMethod;
import com.yourorg.payops.model.PaymentStatus;
import com.yourorg.payops.model.SwitchProtocol;
import com.yourorg.payops.model.SwitchStatus;
import com.yourorg.payops.model.SwitchType;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.MerchantRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.PostingRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrency_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PaymentConcurrencyTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

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

    @BeforeEach
    void setUp() {
        postingRepository.deleteAll();
        journalEntryRepository.deleteAll();
        paymentRepository.deleteAll();
        accountRepository.deleteAll();
        merchantRepository.deleteAll();
        switchDatabaseRepository.deleteAll();

        // Seed accounts 1100 and 2100
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

        // Seed switch
        SwitchDatabase sw = new SwitchDatabase();
        sw.setCode("SW-FAKE-1");
        sw.setName("Fake Bank Route");
        sw.setSwitchType(SwitchType.BANK);
        sw.setProtocol(SwitchProtocol.REST);
        sw.setStatus(SwitchStatus.ACTIVE);
        sw.setPriority(1);
        sw.setTimeoutMs(5000);
        switchDatabaseRepository.save(sw);

        // Seed merchant
        merchant = new Merchant();
        merchant.setCode("M-CONCURRENT");
        merchant.setName("Concurrent Merchant");
        merchant.setStatus(MerchantStatus.ACTIVE);
        merchant.setDefaultSwitch(sw);
        merchant = merchantRepository.save(merchant);

        when(bankClient.authorize(any(BankAuthRequest.class)))
                .thenReturn(new BankAuthResponse("BREQ-CONC", "REF-CONC-01", "APPROVED", "BNK-CONC-123", "00", "Approved"));
    }

    @Test
    void concurrentDuplicatePaymentRequestsDoNotCreateDuplicatePaymentsOrLedgers() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Payment>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(); // Synchronize all threads to fire simultaneously

                Payment p = new Payment();
                p.setReference("REF-CONC-01");
                p.setMerchant(merchant);
                p.setAmount(new BigDecimal("100.0000"));
                p.setCurrency("INR");
                p.setMethod(PaymentMethod.CARD);
                p.setCardNumber("4111111111111111");

                return paymentService.processPayment("IDEM-CONCURRENT-KEY", p);
            }));
        }

        List<Payment> results = new ArrayList<>();
        for (Future<Payment> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // All threads received the same payment result
        Long firstPaymentId = results.get(0).getId();
        for (Payment p : results) {
            assertEquals(firstPaymentId, p.getId());
            assertEquals(PaymentStatus.APPROVED, p.getStatus());
        }

        // Database assertions: exactly 1 payment in DB, exactly 1 journal entry, exactly 2 postings
        assertEquals(1, paymentRepository.count(), "Exactly one payment must exist in database");
        assertEquals(1, journalEntryRepository.count(), "Exactly one journal entry must exist in database");
        assertEquals(2, postingRepository.count(), "Exactly two balanced postings must exist in database");
    }
}
