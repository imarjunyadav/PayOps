package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.entity.BankTransaction;
import com.yourorg.fakebank.repository.BankCardRepository;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BankPaymentConcurrencyTest {

    @Autowired
    private BankPaymentService paymentService;

    @Autowired
    private BankCardRepository cardRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    private static final String TEST_CARD = "4111111111111111";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.0000");
    private static final BigDecimal DEBIT_AMOUNT = new BigDecimal("100.0000");

    @BeforeEach
    void setUp() {
        BankCard card = cardRepository.findByCardNumber(TEST_CARD).orElseGet(() -> {
            BankCard c = new BankCard(TEST_CARD, "Concurrency Test", 12, 2028, "123", INITIAL_BALANCE, "INR", "ACTIVE");
            return cardRepository.save(c);
        });
        card.setBalance(INITIAL_BALANCE);
        card.setStatus("ACTIVE");
        cardRepository.saveAndFlush(card);
    }

    @Test
    void concurrentDuplicateRequestsDebitCardExactlyOnceAndReturnIdenticalTransaction() throws Exception {
        String requestId = "REQ-CONCUR-" + UUID.randomUUID();
        String paymentRef = "PAY-CONCUR-" + UUID.randomUUID();

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<BankPaymentResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize all threads to execute at the exact same millisecond
                    BankPaymentRequest request = new BankPaymentRequest(
                            requestId,
                            paymentRef,
                            TEST_CARD,
                            DEBIT_AMOUNT,
                            "INR"
                    );
                    return paymentService.authorize(request);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All 5 concurrent requests must complete within 10 seconds");

        List<BankPaymentResponse> responses = new ArrayList<>();
        for (Future<BankPaymentResponse> future : futures) {
            responses.add(future.get(5, TimeUnit.SECONDS));
        }

        executor.shutdown();

        // 1. Verify every response succeeded and represents the EXACT same bank transaction
        assertEquals(5, responses.size());
        String expectedBankRef = responses.get(0).getBankReference();
        assertNotNull(expectedBankRef, "Bank reference must not be null");

        for (BankPaymentResponse resp : responses) {
            assertEquals("APPROVED", resp.getStatus(), "Every concurrent request must resolve to APPROVED");
            assertEquals("00", resp.getResponseCode(), "Response code must be 00");
            assertEquals(requestId, resp.getRequestId(), "Request ID must match");
            assertEquals(expectedBankRef, resp.getBankReference(), "All threads must return the identical bankReference");
        }

        // 2. Verify exactly ONE database transaction was persisted for this requestId
        Optional<BankTransaction> txOpt = transactionRepository.findByRequestId(requestId);
        assertTrue(txOpt.isPresent(), "Transaction record must exist in database");
        BankTransaction tx = txOpt.get();
        assertEquals("APPROVED", tx.getStatus());
        assertEquals(expectedBankRef, tx.getBankReference());
        assertTrue(tx.isDebited(), "Transaction must mark card as debited");

        // 3. Verify card balance was debited EXACTLY ONCE (50,000 - 100 = 49,900, not 49,500)
        BankCard updatedCard = cardRepository.findByCardNumber(TEST_CARD).orElseThrow();
        BigDecimal expectedBalance = INITIAL_BALANCE.subtract(DEBIT_AMOUNT);
        assertEquals(0, expectedBalance.compareTo(updatedCard.getBalance()),
                "Card balance must be debited exactly once. Expected: " + expectedBalance + ", Actual: " + updatedCard.getBalance());
    }
}
