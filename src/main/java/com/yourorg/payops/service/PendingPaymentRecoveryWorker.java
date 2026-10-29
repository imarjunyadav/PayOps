package com.yourorg.payops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingPaymentRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(PendingPaymentRecoveryWorker.class);

    private final PaymentRecoveryService recoveryService;
    private final boolean enabled;
    private final int batchSize;

    public PendingPaymentRecoveryWorker(PaymentRecoveryService recoveryService,
                                        @Value("${payops.recovery.enabled:true}") boolean enabled,
                                        @Value("${payops.recovery.batch-size:50}") int batchSize) {
        this.recoveryService = recoveryService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payops.recovery.fixed-delay-ms:5000}")
    public void runRecovery() {
        if (!enabled) {
            log.trace("Pending payment recovery worker is disabled");
            return;
        }

        try {
            int recovered = recoveryService.recoverBatch(batchSize);
            if (recovered > 0) {
                log.info("Recovery cycle resolved {} pending payment(s)", recovered);
            }
        } catch (Exception e) {
            log.error("Unhandled error in pending payment recovery cycle: {}", e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
