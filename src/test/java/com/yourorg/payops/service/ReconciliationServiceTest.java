package com.yourorg.payops.service;

import com.yourorg.payops.entity.*;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.*;
import com.yourorg.payops.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReconciliationService.
 * Uses Mockito mocks for all repositories.
 * Verifies RFC-4180 parsing, dual identifier preservation, matching logic,
 * conflict handling, duplicate prevention, eligibility rules for MISSING_IN_BANK,
 * and CSV export.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private SettlementFileRepository settlementFileRepo;
    @Mock private SettlementRecordRepository settlementRecordRepo;
    @Mock private ReconciliationRunRepository reconciliationRunRepo;
    @Mock private ReconciliationResultRepository reconciliationResultRepo;
    @Mock private PaymentRepository paymentRepo;
    @Mock private SwitchDatabaseRepository switchDatabaseRepo;

    private ReconciliationService service;

    private SwitchDatabase switchDb;
    private SettlementFile settlementFile;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(
                settlementFileRepo, settlementRecordRepo,
                reconciliationRunRepo, reconciliationResultRepo,
                paymentRepo, switchDatabaseRepo);
        setField(service, "maxFileSize", 5_242_880L);
        setField(service, "maxRows", 5000);

        switchDb = new SwitchDatabase();
        switchDb.setId(1L);
        lenient().when(switchDatabaseRepo.findById(1L)).thenReturn(Optional.of(switchDb));

        settlementFile = new SettlementFile();
        settlementFile.setId(10L);
        settlementFile.setSwitchDatabase(switchDb);
        settlementFile.setRecordCount(0);
        settlementFile.setTotalAmount(BigDecimal.ZERO);
        settlementFile.setStatus(SettlementFileStatus.PARSED);
    }

    // -----------------------------------------------------------------------
    // CSV Upload & Header tests
    // -----------------------------------------------------------------------

    @Test
    void uploadValidCsvParsesAndPersistsCorrectly() {
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,1000.00,15.00,INR,2026-10-23T10:00:00\n"
                   + "PAY-002,500.50,7.50,INR,2026-10-23T11:00:00\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "test.csv", "text/csv", csv.getBytes());

        when(settlementFileRepo.findByFileName("test.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(10L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, LocalDate.of(2026, 10, 23));

        assertThat(result).isNotNull();
        assertThat(result.getRecordCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("1500.50");
        assertThat(result.getStatus()).isEqualTo(SettlementFileStatus.PARSED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementRecordRepo).saveAll(captor.capture());
        List<SettlementRecord> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getPaymentReference()).isEqualTo("PAY-001");
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("1000.00");
        assertThat(saved.get(0).getFee()).isEqualByComparingTo("15.00");
        assertThat(saved.get(0).getCurrency()).isEqualTo("INR");
    }

    @Test
    void uploadWithMinimumValidHeaderSet() {
        String csv = "Payment Reference,Amount\n"
                   + "PAY-MIN-1,250.00\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "minheaders.csv", "text/csv", csv.getBytes());

        when(settlementFileRepo.findByFileName("minheaders.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(11L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, LocalDate.of(2026, 10, 24));

        assertThat(result.getRecordCount()).isEqualTo(1);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("250.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementRecordRepo).saveAll(captor.capture());
        SettlementRecord record = captor.getValue().get(0);
        assertThat(record.getPaymentReference()).isEqualTo("PAY-MIN-1");
        assertThat(record.getAmount()).isEqualByComparingTo("250.00");
        assertThat(record.getFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.getCurrency()).isEqualTo("INR");
        assertThat(record.getTransactionDate()).isEqualTo(LocalDate.of(2026, 10, 24).atStartOfDay());
    }

    @Test
    void uploadWithBankReferenceAsIdentifier() {
        String csv = "Bank Reference,Amount\n"
                   + "BNK-REC-999,750.00\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "bankref.csv", "text/csv", csv.getBytes());

        when(settlementFileRepo.findByFileName("bankref.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(12L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementRecordRepo).saveAll(captor.capture());
        SettlementRecord record = captor.getValue().get(0);
        assertThat(record.getBankReference()).isEqualTo("BNK-REC-999");
        assertThat(record.getPaymentReference()).isNull();
        assertThat(record.getAmount()).isEqualByComparingTo("750.00");
    }

    @Test
    void uploadWithBothPaymentAndBankReferencesPreservesBoth() {
        String csv = "Payment Reference,Bank Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-DUAL-1,BNK-DUAL-1,450.00,5.00,INR,2026-10-24\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "dualrefs.csv", "text/csv", csv.getBytes());

        when(settlementFileRepo.findByFileName("dualrefs.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(13L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementRecordRepo).saveAll(captor.capture());
        SettlementRecord record = captor.getValue().get(0);
        // Both identifiers are preserved and neither is discarded!
        assertThat(record.getPaymentReference()).isEqualTo("PAY-DUAL-1");
        assertThat(record.getBankReference()).isEqualTo("BNK-DUAL-1");
        assertThat(record.getAmount()).isEqualByComparingTo("450.00");
    }

    @Test
    void uploadParsesQuotedFieldsWithCommasAndEscapedQuotes() {
        String csv = "\"Payment Reference\",\"Amount\",\"Fee\",\"Currency\",\"Transaction Date\"\n"
                   + "\"PAY-001\",\"1,000.00\",\"10.00\",\"INR\",\"2026-10-23T10:00:00\"\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "quoted.csv", "text/csv", csv.getBytes());

        when(settlementFileRepo.findByFileName("quoted.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(15L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, null);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("1000.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(settlementRecordRepo).saveAll(captor.capture());
        SettlementRecord record = captor.getValue().get(0);
        assertThat(record.getPaymentReference()).isEqualTo("PAY-001");
        assertThat(record.getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void uploadRejectsMalformedCsvUnclosedQuote() {
        String csv = "\"Payment Reference\",Amount\n\"PAY-UNCLOSED,100.00\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "unclosed.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("unclosed.csv")).thenReturn(Optional.empty());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("unclosed quote");
    }

    @Test
    void uploadRejectsMissingIdentifierHeader() {
        String csv = "Amount,Fee,Currency,Transaction Date\n1000.00,10.00,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "noid.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("noid.csv")).thenReturn(Optional.empty());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("identifier");
    }

    @Test
    void uploadRejectsMissingAmountHeader() {
        String csv = "Payment Reference,Fee,Currency,Transaction Date\nPAY-001,10.00,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "noamount.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("noamount.csv")).thenReturn(Optional.empty());

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("amount");
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);
        assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(emptyFile, 1L, null));
        verify(settlementFileRepo, never()).save(any());
    }

    @Test
    void uploadRejectsOversizedFile() {
        byte[] largeBytes = new byte[100];
        setField(service, "maxFileSize", 50L);
        MockMultipartFile largeFile = new MockMultipartFile(
                "file", "large.csv", "text/csv", largeBytes);
        assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(largeFile, 1L, null));
        verify(settlementFileRepo, never()).save(any());
    }

    @Test
    void uploadRejectsTooManyRows() {
        setField(service, "maxRows", 2);
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,100,1,INR,2026-10-23\n"
                   + "PAY-002,200,2,INR,2026-10-23\n"
                   + "PAY-003,300,3,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "toomany.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("toomany.csv")).thenReturn(Optional.empty());
        assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        verify(settlementFileRepo, never()).save(any());
    }

    @Test
    void uploadRejectsDuplicateFileName() {
        when(settlementFileRepo.findByFileName("dup.csv")).thenReturn(Optional.of(new SettlementFile()));
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\nPAY-001,100,1,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "dup.csv", "text/csv", csv.getBytes());
        assertThrows(ConflictException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        verify(settlementFileRepo, never()).save(any());
    }

    @Test
    void uploadRejectsDuplicatePaymentReferenceWithinCsv() {
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,1000,10,INR,2026-10-23\n"
                   + "PAY-001,500,5,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "dupref.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("dupref.csv")).thenReturn(Optional.empty());
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("duplicate");
    }

    @Test
    void uploadRejectsDuplicateBankReferenceWithinCsv() {
        String csv = "Bank Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "BNK-001,1000,10,INR,2026-10-23\n"
                   + "BNK-001,500,5,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "dupbankref.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("dupbankref.csv")).thenReturn(Optional.empty());
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("duplicate bank reference");
    }

    @Test
    void uploadRejectsMalformedAmount() {
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,abc,1,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "badamount.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("badamount.csv")).thenReturn(Optional.empty());
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
        assertThat(ex.getMessage()).containsIgnoringCase("amount");
    }

    @Test
    void uploadRejectsNonPositiveAmount() {
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,0.00,0,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "zeramt.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("zeramt.csv")).thenReturn(Optional.empty());
        assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
    }

    @Test
    void uploadRejectsFeeExceedingAmount() {
        String csv = "Payment Reference,Amount,Fee,Currency,Transaction Date\n"
                   + "PAY-001,100.00,200.00,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "bigfee.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("bigfee.csv")).thenReturn(Optional.empty());
        assertThrows(BusinessRuleException.class,
                () -> service.uploadAndProcessCsv(multipart, 1L, null));
    }

    @Test
    void uploadAcceptsDateAlias() {
        String csv = "Payment Reference,Amount,Fee,Currency,Date\n"
                   + "PAY-001,100,1,INR,2026-10-23\n";
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "datealiasfile.csv", "text/csv", csv.getBytes());
        when(settlementFileRepo.findByFileName("datealiasfile.csv")).thenReturn(Optional.empty());
        when(settlementFileRepo.save(any())).thenAnswer(inv -> {
            SettlementFile f = inv.getArgument(0);
            f.setId(21L);
            return f;
        });
        when(settlementRecordRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        SettlementFile result = service.uploadAndProcessCsv(multipart, 1L, null);
        assertThat(result.getRecordCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Reconciliation run tests: matching, dual references, conflict handling
    // -----------------------------------------------------------------------

    @Test
    void runReconciliationPaymentReferenceOnlyCase() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-001", null, "1000.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment payment = payment(100L, "PAY-001", null, "1000.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getExceptionCount()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.MATCHED);
    }

    @Test
    void runReconciliationBankReferenceOnlyCase() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, null, "BNK-XYZ", "500.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment payment = payment(100L, "PAY-001", "BNK-XYZ", "500.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getExceptionCount()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.MATCHED);
    }

    @Test
    void runReconciliationBothIdentifiersSuppliedWithOnlyBankReferenceMatching() {
        // CSV row has both: an unknown/unmatched paymentReference, but a VALID bankReference
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "UNKNOWN-PAY-REF", "BNK-MATCH-01", "300.00", "INR");
        bankRecord.setSettlementFile(settlementFile);

        Payment payment = payment(100L, "PAY-REAL-01", "BNK-MATCH-01", "300.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        // Bank reference match is preserved and NOT discarded!
        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getExceptionCount()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.MATCHED);
        assertThat(results.get(0).getPayment().getReference()).isEqualTo("PAY-REAL-01");
    }

    @Test
    void runReconciliationBothIdentifiersSuppliedAndResolvingToSamePayment() {
        // Both paymentReference and bankReference match the same payment in switch
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-001", "BNK-001", "100.00", "INR");
        bankRecord.setSettlementFile(settlementFile);

        Payment payment = payment(100L, "PAY-001", "BNK-001", "100.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        // Must treat as exactly ONE match
        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getExceptionCount()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.MATCHED);
    }

    @Test
    void runReconciliationIdentifiersResolvingToDifferentPaymentsRecordsDiscrepancy() {
        // Row has payRef="PAY-001" and bankRef="BNK-002"
        // In switch:
        // P1 has reference="PAY-001", bankRef="BNK-001"
        // P2 has reference="PAY-002", bankRef="BNK-002"
        // They resolve to DIFFERENT payments! Must not silently choose one.
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-001", "BNK-002", "100.00", "INR");
        bankRecord.setSettlementFile(settlementFile);

        Payment p1 = payment(101L, "PAY-001", "BNK-001", "100.00", "INR");
        Payment p2 = payment(102L, "PAY-002", "BNK-002", "100.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(p1, p2));

        ReconciliationRun run = service.runReconciliation(10L);

        // Does NOT count as a match; recorded as DUPLICATE / conflict discrepancy
        assertThat(run.getMatchedCount()).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();

        // 1 conflict discrepancy result for bank record + 2 MISSING_IN_BANK for p1 & p2
        ReconciliationResult bankRecResult = results.stream()
                .filter(r -> r.getSettlementRecord() != null)
                .findFirst().orElseThrow();
        assertThat(bankRecResult.getResultType()).isEqualTo(ResultType.DUPLICATE);
        assertThat(bankRecResult.getNote()).containsIgnoringCase("conflicting references");

        // Neither payment was claimed as matched, so both eligible payments are flagged as MISSING_IN_BANK
        List<ReconciliationResult> missingInBank = results.stream()
                .filter(r -> r.getResultType() == ResultType.MISSING_IN_BANK)
                .toList();
        assertThat(missingInBank).hasSize(2);
    }

    @Test
    void runReconciliationAmountMismatch() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-001", null, "900.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment payment = payment(100L, "PAY-001", null, "1000.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        assertThat(run.getMatchedCount()).isEqualTo(0);
        assertThat(run.getExceptionCount()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.AMOUNT_MISMATCH);
        assertThat(results.get(0).getExpectedAmount()).isEqualByComparingTo("1000.00");
        assertThat(results.get(0).getActualAmount()).isEqualByComparingTo("900.00");
    }

    @Test
    void runReconciliationMissingInPayments() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-UNKNOWN", null, "500.00", "INR");
        bankRecord.setSettlementFile(settlementFile);

        stubRunScenario(List.of(bankRecord), List.of());

        ReconciliationRun run = service.runReconciliation(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResultType()).isEqualTo(ResultType.MISSING_IN_PAYMENTS);
        assertThat(results.get(0).getActualAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void runReconciliationMissingInBank() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-002", null, "200.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment pay1 = payment(100L, "PAY-002", null, "200.00", "INR");
        Payment pay2 = payment(101L, "PAY-003", null, "999.00", "INR"); // no bank record

        stubRunScenario(List.of(bankRecord), List.of(pay1, pay2));

        ReconciliationRun run = service.runReconciliation(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(2);

        long missingInBank = results.stream()
                .filter(r -> r.getResultType() == ResultType.MISSING_IN_BANK).count();
        long matched = results.stream()
                .filter(r -> r.getResultType() == ResultType.MATCHED).count();
        assertThat(missingInBank).isEqualTo(1);
        assertThat(matched).isEqualTo(1);
    }

    @Test
    void runReconciliationExcludesPendingAndDeclinedPaymentsFromMissingInBank() {
        Payment p1 = payment(101L, "PAY-APP-1", null, "100.00", "INR");
        p1.setStatus(PaymentStatus.APPROVED);

        Payment p2 = payment(102L, "PAY-PEND-1", null, "200.00", "INR");
        p2.setStatus(PaymentStatus.PENDING);

        Payment p3 = payment(103L, "PAY-DECL-1", null, "300.00", "INR");
        p3.setStatus(PaymentStatus.DECLINED);

        Payment p4 = payment(104L, "PAY-FAIL-1", null, "400.00", "INR");
        p4.setStatus(PaymentStatus.FAILED);

        Payment p5 = payment(105L, "PAY-INIT-1", null, "500.00", "INR");
        p5.setStatus(PaymentStatus.INITIATED);

        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-OTHER", null, "50.00", "INR");
        bankRecord.setSettlementFile(settlementFile);

        stubRunScenario(List.of(bankRecord), List.of(p1, p2, p3, p4, p5));

        ReconciliationRun run = service.runReconciliation(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();

        List<ReconciliationResult> missingInBankResults = results.stream()
                .filter(r -> r.getResultType() == ResultType.MISSING_IN_BANK)
                .toList();

        assertThat(missingInBankResults).hasSize(1);
        assertThat(missingInBankResults.get(0).getPayment().getReference()).isEqualTo("PAY-APP-1");

        List<Long> resultPaymentIds = results.stream()
                .filter(r -> r.getPayment() != null)
                .map(r -> r.getPayment().getId())
                .toList();
        assertThat(resultPaymentIds).doesNotContain(102L, 103L, 104L, 105L);
    }

    @Test
    void runReconciliationMultipleBankRecordsMatchingSamePaymentProducesDuplicateResult() {
        SettlementRecord rec1 = settlementRecordWithBoth(1L, "PAY-001", null, "100.00", "INR");
        rec1.setSettlementFile(settlementFile);

        SettlementRecord rec2 = settlementRecordWithBoth(2L, null, "BNK-001", "100.00", "INR");
        rec2.setSettlementFile(settlementFile);

        Payment payment = payment(100L, "PAY-001", "BNK-001", "100.00", "INR");

        stubRunScenario(List.of(rec1, rec2), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();

        assertThat(results).hasSize(2);

        ReconciliationResult r1 = results.get(0);
        assertThat(r1.getSettlementRecord().getId()).isEqualTo(1L);
        assertThat(r1.getResultType()).isEqualTo(ResultType.MATCHED);

        ReconciliationResult r2 = results.get(1);
        assertThat(r2.getSettlementRecord().getId()).isEqualTo(2L);
        assertThat(r2.getResultType()).isEqualTo(ResultType.DUPLICATE);
        assertThat(r2.getNote()).containsIgnoringCase("duplicate bank match");

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getExceptionCount()).isEqualTo(1);
    }

    @Test
    void runReconciliationDoesNotMatchOnAmountAloneWithNoIdentifier() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "UNKNOWN-REF", null, "1000.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment payment = payment(100L, "PAY-001", null, "1000.00", "INR");

        stubRunScenario(List.of(bankRecord), List.of(payment));

        ReconciliationRun run = service.runReconciliation(10L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReconciliationResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(reconciliationResultRepo).saveAll(captor.capture());
        List<ReconciliationResult> results = captor.getValue();
        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(r -> r.getResultType() == ResultType.MISSING_IN_PAYMENTS).count())
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getResultType() == ResultType.MISSING_IN_BANK).count())
                .isEqualTo(1);
    }

    @Test
    void runReconciliationRejectsFileWithNoRecords() {
        when(settlementFileRepo.findById(10L)).thenReturn(Optional.of(settlementFile));
        when(settlementRecordRepo.findBySettlementFileId(10L)).thenReturn(List.of());
        assertThrows(BusinessRuleException.class, () -> service.runReconciliation(10L));
        verify(reconciliationResultRepo, never()).saveAll(any());
    }

    @Test
    void runReconciliationThrowsWhenFileNotFound() {
        when(settlementFileRepo.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.runReconciliation(404L));
    }

    @Test
    void runReconciliationDoesNotAlterPaymentStatus() {
        SettlementRecord bankRecord = settlementRecordWithBoth(1L, "PAY-001", null, "1000.00", "INR");
        bankRecord.setSettlementFile(settlementFile);
        Payment payment = payment(100L, "PAY-001", null, "1000.00", "INR");
        PaymentStatus originalStatus = payment.getStatus();

        stubRunScenario(List.of(bankRecord), List.of(payment));

        service.runReconciliation(10L);

        assertThat(payment.getStatus()).isEqualTo(originalStatus);
        verify(paymentRepo, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // CSV Export tests
    // -----------------------------------------------------------------------

    @Test
    void exportResultsCsvContainsHeader() {
        ReconciliationRun run = new ReconciliationRun();
        run.setId(5L);
        when(reconciliationRunRepo.findById(5L)).thenReturn(Optional.of(run));
        when(reconciliationResultRepo.findByReconciliationRunId(5L)).thenReturn(List.of());

        byte[] csv = service.exportResultsCsv(5L);
        String content = new String(csv);
        assertThat(content).startsWith("Result ID,Result Type,Payment Reference,Expected Amount,Actual Amount,Note");
    }

    @Test
    void exportResultsCsvIncludesMatchedRow() {
        ReconciliationRun run = new ReconciliationRun();
        run.setId(5L);
        Payment p = payment(100L, "PAY-001", null, "1000.00", "INR");
        ReconciliationResult result = new ReconciliationResult();
        result.setId(1L);
        result.setResultType(ResultType.MATCHED);
        result.setPayment(p);
        result.setExpectedAmount(new BigDecimal("1000.00"));
        result.setActualAmount(new BigDecimal("1000.00"));

        when(reconciliationRunRepo.findById(5L)).thenReturn(Optional.of(run));
        when(reconciliationResultRepo.findByReconciliationRunId(5L)).thenReturn(List.of(result));

        byte[] csv = service.exportResultsCsv(5L);
        String content = new String(csv);
        assertThat(content).contains("MATCHED");
        assertThat(content).contains("PAY-001");
        assertThat(content).contains("1000.00");
    }

    @Test
    void exportResultsCsvEscapesQuotesAndCommas() {
        ReconciliationRun run = new ReconciliationRun();
        run.setId(5L);
        Payment p = payment(100L, "PAY-001", null, "1000.00", "INR");
        ReconciliationResult result = new ReconciliationResult();
        result.setId(2L);
        result.setResultType(ResultType.AMOUNT_MISMATCH);
        result.setPayment(p);
        result.setExpectedAmount(new BigDecimal("1000.00"));
        result.setActualAmount(new BigDecimal("900.00"));
        result.setNote("Discrepancy: bank fee was 10.00, client said \"verified\"");

        when(reconciliationRunRepo.findById(5L)).thenReturn(Optional.of(run));
        when(reconciliationResultRepo.findByReconciliationRunId(5L)).thenReturn(List.of(result));

        byte[] csv = service.exportResultsCsv(5L);
        String content = new String(csv);
        assertThat(content).contains("\"Discrepancy: bank fee was 10.00, client said \"\"verified\"\"\"");
    }

    @Test
    void exportThrowsWhenRunNotFound() {
        when(reconciliationRunRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.exportResultsCsv(999L));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubRunScenario(List<SettlementRecord> bankRecords, List<Payment> payments) {
        when(settlementFileRepo.findById(10L)).thenReturn(Optional.of(settlementFile));
        when(settlementRecordRepo.findBySettlementFileId(10L)).thenReturn(bankRecords);
        when(paymentRepo.findBySwitchDatabaseId(1L)).thenReturn(payments);
        when(reconciliationRunRepo.save(any())).thenAnswer(inv -> {
            ReconciliationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(50L);
            return r;
        });
        when(reconciliationResultRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementFileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SettlementRecord settlementRecordWithBoth(Long id, String payRef, String bankRef, String amount, String currency) {
        SettlementRecord record = new SettlementRecord();
        record.setId(id);
        record.setPaymentReference(payRef);
        record.setBankReference(bankRef);
        record.setAmount(new BigDecimal(amount));
        record.setFee(BigDecimal.ZERO);
        record.setCurrency(currency);
        return record;
    }

    private Payment payment(Long id, String reference, String bankRef, String amount, String currency) {
        Payment p = new Payment();
        p.setId(id);
        p.setReference(reference);
        p.setBankReference(bankRef);
        p.setAmount(new BigDecimal(amount));
        p.setCurrency(currency);
        p.setStatus(PaymentStatus.APPROVED);
        return p;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field: " + name, e);
        }
    }
}
