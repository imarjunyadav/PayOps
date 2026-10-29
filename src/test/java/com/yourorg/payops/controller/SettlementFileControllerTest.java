package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.SettlementFile;
import com.yourorg.payops.entity.SettlementRecord;
import com.yourorg.payops.entity.SwitchDatabase;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.SettlementFileStatus;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.SettlementFileRepository;
import com.yourorg.payops.repository.SettlementRecordRepository;
import com.yourorg.payops.repository.SwitchDatabaseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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

class SettlementFileControllerTest {
    private SettlementFileRepository files;
    private SettlementRecordRepository records;
    private SwitchDatabaseRepository switches;
    private PaymentRepository payments;
    private SettlementFileController controller;
    private SettlementFile file;
    private SwitchDatabase network;

    @BeforeEach
    void setUp() {
        files = mock(SettlementFileRepository.class);
        records = mock(SettlementRecordRepository.class);
        switches = mock(SwitchDatabaseRepository.class);
        payments = mock(PaymentRepository.class);
        controller = new SettlementFileController(files, records, switches, payments,
                mock(com.yourorg.payops.service.ReconciliationService.class));

        network = new SwitchDatabase();
        network.setId(8L);
        file = new SettlementFile();
        file.setId(10L);
        file.setFileName("settlement-2026-10-23.csv");
        file.setRecordCount(0);
        file.setTotalAmount(BigDecimal.ZERO);
        when(files.findById(10L)).thenReturn(Optional.of(file));
        when(switches.findById(8L)).thenReturn(Optional.of(network));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/settlement-files");
        request.setServerName("localhost");
        request.setServerPort(8080);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsSettlementFileWithServerManagedFields() {
        when(files.findByFileName("settlement-new.csv")).thenReturn(Optional.empty());
        when(files.save(any(SettlementFile.class))).thenAnswer(call -> {
            SettlementFile saved = call.getArgument(0);
            saved.setId(21L);
            return saved;
        });
        SettlementFile request = new SettlementFile();
        request.setId(99L);
        request.setFileName("settlement-new.csv");
        request.setSwitchDatabase(network);
        request.setSettlementDate(LocalDate.of(2026, 10, 23));
        request.setStatus(SettlementFileStatus.RECONCILED);
        request.setRecordCount(42);
        request.setTotalAmount(new BigDecimal("1000"));

        var response = controller.create(request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(21L, response.getBody().getId());
        assertEquals(SettlementFileStatus.RECEIVED, response.getBody().getStatus());
        assertEquals(0, response.getBody().getRecordCount());
        assertEquals(BigDecimal.ZERO, response.getBody().getTotalAmount());
        assertNotNull(response.getBody().getReceivedAt());
    }

    @Test
    void rejectsDuplicateSettlementFileName() {
        SettlementFile request = new SettlementFile();
        request.setFileName("settlement-2026-10-23.csv");
        when(files.findByFileName(request.getFileName())).thenReturn(Optional.of(file));

        assertThrows(ConflictException.class, () -> controller.create(request));
        verify(files, never()).save(any());
    }

    @Test
    void rejectsRecordsForMissingSettlementFile() {
        when(files.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> controller.addRecords(404L, List.of(validRecord())));
    }

    @Test
    void createsRecordsAndUpdatesFileTotals() {
        Payment payment = new Payment();
        payment.setId(5L);
        when(payments.findById(5L)).thenReturn(Optional.of(payment));
        when(records.saveAll(any(Iterable.class))).thenAnswer(call -> call.getArgument(0));
        SettlementRecord request = validRecord();
        request.setPayment(payment);
        request.setSettlementFile(new SettlementFile());

        var response = controller.addRecords(10L, List.of(request));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(file, request.getSettlementFile());
        assertEquals(payment, request.getPayment());
        assertEquals(1, file.getRecordCount());
        assertEquals(new BigDecimal("125.5000"), file.getTotalAmount());
        verify(records).saveAll(List.of(request));
        verify(files).save(file);
    }

    @Test
    void rejectsNonPositiveRecordAmount() {
        SettlementRecord request = validRecord();
        request.setAmount(BigDecimal.ZERO);
        assertThrows(BusinessRuleException.class, () -> controller.addRecords(10L, List.of(request)));
        verify(records, never()).saveAll(any());
    }

    @Test
    void rejectsFeeGreaterThanRecordAmount() {
        SettlementRecord request = validRecord();
        request.setFee(new BigDecimal("125.5001"));
        assertThrows(BusinessRuleException.class, () -> controller.addRecords(10L, List.of(request)));
        verify(records, never()).saveAll(any());
    }

    @Test
    void rejectsNegativeFee() {
        SettlementRecord request = validRecord();
        request.setFee(new BigDecimal("-0.01"));
        assertThrows(BusinessRuleException.class, () -> controller.addRecords(10L, List.of(request)));
        verify(records, never()).saveAll(any());
    }

    @Test
    void rejectsMissingReferencedPayment() {
        Payment supplied = new Payment();
        supplied.setId(999L);
        SettlementRecord request = validRecord();
        request.setPayment(supplied);
        when(payments.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.addRecords(10L, List.of(request)));
        verify(records, never()).saveAll(any());
    }

    @Test
    void recordBatchWriteIsTransactional() throws Exception {
        var method = SettlementFileController.class.getMethod("addRecords", Long.class, List.class);
        assertNotNull(method.getAnnotation(Transactional.class));
    }

    private SettlementRecord validRecord() {
        SettlementRecord record = new SettlementRecord();
        record.setPaymentReference("bank-ref-001");
        record.setAmount(new BigDecimal("125.5000"));
        record.setFee(new BigDecimal("2.50"));
        record.setCurrency("INR");
        return record;
    }
}
