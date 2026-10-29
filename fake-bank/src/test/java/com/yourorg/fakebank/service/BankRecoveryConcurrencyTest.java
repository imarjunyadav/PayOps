package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.entity.BankTransaction;
import com.yourorg.fakebank.repository.BankCardRepository;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BankRecoveryConcurrencyTest {

    @Autowired
    private BankTransactionClaimService claimService;

    @Autowired
    private BankCardRepository cardRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    private static final String TEST_CARD = "4111111111111111";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.0000");
    private static final BigDecimal AMOUNT = new BigDecimal("100.0000");

    @BeforeEach
    void setUp() {
        BankCard card = cardRepository.findByCardNumber(TEST_CARD).orElseGet(() -> {
            BankCard c = new BankCard(TEST_CARD, "Recovery Test", 12, 2028, "123", INITIAL_BALANCE, "INR", "ACTIVE");
            return cardRepository.save(c);
        });
        card.setBalance(INITIAL_BALANCE);
        card.setStatus("ACTIVE");
        cardRepository.saveAndFlush(card);
    }

    @Test
    void concurrentRecoveryAndFinalizationDebitsCardExactlyOnce() throws Exception {
        String requestId = "REQ-REC-" + UUID.randomUUID();
        String paymentRef = "PAY-RC-" + UUID.randomUUID();
        String bankRef = "BNK-REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 1. Pre-create a durable PENDING transaction as would exist during an in-flight delay or crash
        BankTransaction pendingTx = claimService.claim(
                requestId,
                paymentRef,
                AMOUNT,
                "INR",
                bankRef,
                TEST_CARD
        );
        Long txId = pendingTx.getId();
        assertNotNull(txId);
        assertEquals("PENDING", pendingTx.getStatus());
        assertFalse(pendingTx.isDebited());

        // 2. Launch 5 concurrent threads (simulating delayed worker + multiple concurrent status/recovery requests)
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<BankTransaction>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize all recovery attempts
                    BankPaymentRequest request = new BankPaymentRequest(
                            requestId,
                            paymentRef,
                            TEST_CARD,
                            AMOUNT,
                            "INR"
                    );
                    return claimService.processDebitAndFinalize(txId, TEST_CARD, request);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Trigger all concurrent recovery workers simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All concurrent recovery attempts must complete within 10 seconds");

        List<BankTransaction> results = new ArrayList<>();
        for (Future<BankTransaction> f : futures) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }

        executor.shutdown();

        // 3. Verify all callers received an APPROVED result
        assertEquals(5, results.size());
        for (BankTransaction res : results) {
            assertEquals("APPROVED", res.getStatus());
            assertEquals("00", res.getResponseCode());
            assertTrue(res.isDebited());
            assertEquals(bankRef, res.getBankReference());
        }

        // 4. Verify card balance was debited EXACTLY ONCE (50,000 - 100 = 49,900, not 49,500)
        BankCard updatedCard = cardRepository.findByCardNumber(TEST_CARD).orElseThrow();
        BigDecimal expectedBalance = INITIAL_BALANCE.subtract(AMOUNT);
        assertEquals(0, expectedBalance.compareTo(updatedCard.getBalance()),
                "Card balance must be debited exactly once under concurrent recovery. Expected: " + expectedBalance + ", Actual: " + updatedCard.getBalance());
    }
}
