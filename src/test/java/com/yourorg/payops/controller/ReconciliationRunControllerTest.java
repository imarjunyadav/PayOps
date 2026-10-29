package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.ReconciliationResult;
import com.yourorg.payops.entity.ReconciliationRun;
import com.yourorg.payops.entity.SettlementFile;
import com.yourorg.payops.entity.SettlementRecord;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.ResultType;
import com.yourorg.payops.model.RunStatus;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.ReconciliationResultRepository;
import com.yourorg.payops.repository.ReconciliationRunRepository;
import com.yourorg.payops.repository.SettlementFileRepository;
import com.yourorg.payops.repository.SettlementRecordRepository;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationRunControllerTest {
    private ReconciliationRunRepository runs;
    private ReconciliationResultRepository results;
    private SettlementFileRepository files;
    private SettlementRecordRepository settlementRecords;
    private PaymentRepository payments;
    private ReconciliationRunController controller;
    private SettlementFile file;
    private ReconciliationRun run;

    @BeforeEach
    void setUp() {
        runs = mock(ReconciliationRunRepository.class);
        results = mock(ReconciliationResultRepository.class);
        files = mock(SettlementFileRepository.class);
        settlementRecords = mock(SettlementRecordRepository.class);
        payments = mock(PaymentRepository.class);
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var reconciliationService = mock(com.yourorg.payops.service.ReconciliationService.class);
        controller = new ReconciliationRunController(runs, results, files, settlementRecords, payments, validator, reconciliationService);

        file = new SettlementFile();
        file.setId(7L);
        run = new ReconciliationRun();
        run.setId(11L);
        run.setRunNumber("run-11");
        run.setSettlementFile(file);
        when(runs.findById(11L)).thenReturn(Optional.of(run));
        when(files.findById(7L)).thenReturn(Optional.of(file));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/reconciliation-runs");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsRunWithServerManagedInitialState() {
        when(runs.findByRunNumber("run-new")).thenReturn(Optional.empty());
        when(runs.save(any(ReconciliationRun.class))).thenAnswer(call -> {
            ReconciliationRun saved = call.getArgument(0);
            saved.setId(21L);
            return saved;
        });
        ReconciliationRun request = new ReconciliationRun();
        request.setId(99L);
        request.setRunNumber("run-new");
        request.setSettlementFile(file);
        request.setStatus(RunStatus.COMPLETED);
        request.setStartedAt(LocalDateTime.parse("2020-01-01T00:00:00"));
        request.setCompletedAt(LocalDateTime.parse("2020-01-02T00:00:00"));
        request.setTotalChecked(9);
        request.setMatchedCount(7);
        request.setExceptionCount(2);

        var response = controller.create(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(21L, response.getBody().getId());
        assertEquals(RunStatus.RUNNING, response.getBody().getStatus());
        assertNotNull(response.getBody().getStartedAt());
        assertEquals(null, response.getBody().getCompletedAt());
        assertEquals(0, response.getBody().getTotalChecked());
        assertEquals(0, response.getBody().getMatchedCount());
        assertEquals(0, response.getBody().getExceptionCount());
    }

    @Test
    void rejectsMissingRunWhenAddingResults() {
        when(runs.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> controller.addResults(404L, List.of(new ReconciliationResult())));
    }

    @Test
    void createsResultAndResolvesReferencesFromRepositories() {
        Payment supplied = payment(5L, "50.00");
        Payment actualPayment = payment(5L, "50.0000");
        SettlementRecord suppliedRecord = settlementRecord(8L, "50.00", file);
        SettlementRecord actualRecord = settlementRecord(8L, "50.0000", file);
        when(payments.findById(5L)).thenReturn(Optional.of(actualPayment));
        when(settlementRecords.findById(8L)).thenReturn(Optional.of(actualRecord));
        when(results.saveAll(any(Iterable.class))).thenAnswer(call -> call.getArgument(0));
        ReconciliationResult request = new ReconciliationResult();
        request.setPayment(supplied);
        request.setSettlementRecord(suppliedRecord);

        var response = controller.addResults(11L, List.of(request));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(run, request.getReconciliationRun());
        assertEquals(actualPayment, request.getPayment());
        assertEquals(actualRecord, request.getSettlementRecord());
        assertEquals(ResultType.MATCHED, request.getResultType());
        assertEquals(new BigDecimal("50.0000"), request.getExpectedAmount());
        assertEquals(new BigDecimal("50.0000"), request.getActualAmount());
        assertEquals(1, run.getTotalChecked());
        assertEquals(1, run.getMatchedCount());
        assertEquals(0, run.getExceptionCount());
        verify(runs).save(run);
    }

    @Test
    void classifiesDifferentPaymentAndSettlementAmountsAsMismatch() {
        Payment payment = payment(5L, "52.00");
        SettlementRecord record = settlementRecord(8L, "50.00", file);
        when(payments.findById(5L)).thenReturn(Optional.of(payment));
        when(settlementRecords.findById(8L)).thenReturn(Optional.of(record));
        when(results.saveAll(any(Iterable.class))).thenAnswer(call -> call.getArgument(0));
        ReconciliationResult request = new ReconciliationResult();
        request.setPayment(payment(5L, "1.00"));
        request.setSettlementRecord(settlementRecord(8L, "1.00", file));

        controller.addResults(11L, List.of(request));

        assertEquals(ResultType.AMOUNT_MISMATCH, request.getResultType());
        assertEquals(new BigDecimal("52.00"), request.getExpectedAmount());
        assertEquals(new BigDecimal("50.00"), request.getActualAmount());
        assertEquals(0, run.getMatchedCount());
        assertEquals(1, run.getExceptionCount());
    }

    @Test
    void rejectsResultWithNoPaymentOrSettlementRecord() {
        ReconciliationResult request = new ReconciliationResult();
        assertThrows(BusinessRuleException.class, () -> controller.addResults(11L, List.of(request)));
        verify(results, never()).saveAll(any());
    }

    @Test
    void rejectsMissingReferencedPayment() {
        ReconciliationResult request = new ReconciliationResult();
        request.setPayment(payment(404L, "1.00"));
        when(payments.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> controller.addResults(11L, List.of(request)));
        verify(results, never()).saveAll(any());
    }

    @Test
    void rejectsMissingReferencedSettlementRecord() {
        ReconciliationResult request = new ReconciliationResult();
        request.setSettlementRecord(settlementRecord(404L, "1.00", file));
        when(settlementRecords.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> controller.addResults(11L, List.of(request)));
        verify(results, never()).saveAll(any());
    }

    @Test
    void rejectsSettlementRecordFromAnotherFile() {
        SettlementFile otherFile = new SettlementFile();
        otherFile.setId(99L);
        SettlementRecord record = settlementRecord(8L, "50.00", otherFile);
        when(settlementRecords.findById(8L)).thenReturn(Optional.of(record));
        ReconciliationResult request = new ReconciliationResult();
        request.setSettlementRecord(settlementRecord(8L, "50.00", otherFile));

        assertThrows(BusinessRuleException.class, () -> controller.addResults(11L, List.of(request)));
        verify(results, never()).saveAll(any());
    }

    @Test
    void resultWriteIsTransactional() throws Exception {
        var method = ReconciliationRunController.class.getMethod("addResults", Long.class, List.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    private Payment payment(Long id, String amount) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setAmount(new BigDecimal(amount));
        return payment;
    }

    private SettlementRecord settlementRecord(Long id, String amount, SettlementFile file) {
        SettlementRecord record = new SettlementRecord();
        record.setId(id);
        record.setSettlementFile(file);
        record.setAmount(new BigDecimal(amount));
        return record;
    }
}
