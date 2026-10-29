package com.yourorg.payops.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PendingPaymentRecoveryWorkerTest {

    @Mock
    private PaymentRecoveryService recoveryService;

    @Test
    void workerExecutesBatchWhenEnabled() {
        PendingPaymentRecoveryWorker worker = new PendingPaymentRecoveryWorker(recoveryService, true, 25);
        assertTrue(worker.isEnabled());
        assertEquals(25, worker.getBatchSize());

        when(recoveryService.recoverBatch(25)).thenReturn(3);

        worker.runRecovery();

        verify(recoveryService, times(1)).recoverBatch(25);
    }

    @Test
    void workerSkipsExecutionWhenDisabled() {
        PendingPaymentRecoveryWorker worker = new PendingPaymentRecoveryWorker(recoveryService, false, 50);
        assertFalse(worker.isEnabled());

        worker.runRecovery();

        verify(recoveryService, never()).recoverBatch(anyInt());
    }
}
