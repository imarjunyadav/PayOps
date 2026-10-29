package com.yourorg.fakebank.service;

import com.yourorg.fakebank.dto.BankPaymentRequest;
import com.yourorg.fakebank.dto.BankPaymentResponse;
import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.repository.BankCardRepository;
import com.yourorg.fakebank.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "fakebank.delayed-card.delay-ms=1000")
class BankDelayedRequestTest {

    @Autowired
    private BankPaymentService paymentService;

    @Autowired
    private BankCardRepository cardRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    private static final String DELAYED_CARD = "4000000000000259";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.0000");
    private static final BigDecimal AMOUNT = new BigDecimal("100.0000");

    @BeforeEach
    void setUp() {
        BankCard card = cardRepository.findByCardNumber(DELAYED_CARD).orElseGet(() -> {
            BankCard c = new BankCard(DELAYED_CARD, "Delayed Timeout Demo", 12, 2028, "123", INITIAL_BALANCE, "INR", "ACTIVE");
            return cardRepository.save(c);
        });
        card.setBalance(INITIAL_BALANCE);
        card.setStatus("ACTIVE");
        cardRepository.saveAndFlush(card);
    }

    @Test
    void delayedRequestLifecycle_statusLookupDuringDelay_repeatedRequests_andFinalResult() throws Exception {
        String requestId = "REQ-DELAY-" + UUID.randomUUID();
        String paymentRef = "PAY-DELAY-" + UUID.randomUUID();

        BankPaymentRequest request = new BankPaymentRequest(
                requestId,
                paymentRef,
                DELAYED_CARD,
                AMOUNT,
                "INR"
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();

        // 1. Submit authorization in background thread (will delay for 1000ms)
        Future<BankPaymentResponse> backgroundAuth = executor.submit(() -> paymentService.authorize(request));

        // Wait briefly (200ms) to ensure background thread has claimed the request and started delay
        Thread.sleep(200);

        // 2. Status lookup DURING the delay: verify request is found and reported as PENDING (not 404)
        Optional<BankPaymentResponse> intermediateStatus = paymentService.getStatus(requestId);
        assertTrue(intermediateStatus.isPresent(), "Status lookup during delay must find durable record (never 404)");
        assertEquals("PENDING", intermediateStatus.get().getStatus(), "Status during delay must report PENDING");
        assertEquals("PENDING", intermediateStatus.get().getResponseCode());
        assertEquals(requestId, intermediateStatus.get().getRequestId());

        // 3. Repeated request DURING processing: verify it returns PENDING and does not debit or duplicate
        BankPaymentResponse retryResp = paymentService.authorize(request);
        assertEquals("PENDING", retryResp.getStatus(), "Retry during processing must return PENDING");

        // Verify balance has NOT been debited yet while still in delay
        BankCard midFlightCard = cardRepository.findByCardNumber(DELAYED_CARD).orElseThrow();
        assertEquals(0, INITIAL_BALANCE.compareTo(midFlightCard.getBalance()),
                "Card balance must NOT be debited while request is in PENDING delay");

        // 4. Await completion of original background authorization
        BankPaymentResponse finalAuthResp = backgroundAuth.get(4, TimeUnit.SECONDS);
        assertEquals("APPROVED", finalAuthResp.getStatus(), "Background authorization must complete as APPROVED");
        assertEquals("00", finalAuthResp.getResponseCode());

        // 5. Final result retrieval via getStatus
        Optional<BankPaymentResponse> finalLookup = paymentService.getStatus(requestId);
        assertTrue(finalLookup.isPresent(), "Final status lookup must succeed");
        assertEquals("APPROVED", finalLookup.get().getStatus(), "Final status must be APPROVED");
        assertEquals("00", finalLookup.get().getResponseCode());
        assertEquals(finalAuthResp.getBankReference(), finalLookup.get().getBankReference());

        // 6. Repeated request AFTER completion: returns cached APPROVED result
        BankPaymentResponse postCompletionRetry = paymentService.authorize(request);
        assertEquals("APPROVED", postCompletionRetry.getStatus());
        assertEquals(finalAuthResp.getBankReference(), postCompletionRetry.getBankReference());

        // 7. Verify card balance was debited EXACTLY ONCE
        BankCard finalCard = cardRepository.findByCardNumber(DELAYED_CARD).orElseThrow();
        BigDecimal expectedBalance = INITIAL_BALANCE.subtract(AMOUNT);
        assertEquals(0, expectedBalance.compareTo(finalCard.getBalance()),
                "Card balance must be debited exactly once after delayed authorization finishes");

        executor.shutdown();
    }
}
