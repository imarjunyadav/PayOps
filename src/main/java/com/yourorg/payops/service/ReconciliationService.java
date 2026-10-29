package com.yourorg.payops.service;

import com.yourorg.payops.entity.*;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.*;
import com.yourorg.payops.repository.*;
import com.yourorg.payops.util.CsvParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Encapsulates all CSV-based bank settlement reconciliation logic:
 *   1. uploadAndProcessCsv  – validates and persists a settlement CSV.
 *   2. runReconciliation    – matches records to payments and persists results.
 *   3. exportResultsCsv     – produces an RFC 4180 CSV byte array of reconciliation results.
 *
 * Invariants:
 *   - Both Payment Reference and Bank Reference are preserved through reconciliation.
 *   - If both identifiers resolve to the same payment, it is treated as a single match.
 *   - If identifiers resolve to different payments, a DUPLICATE / conflict discrepancy is recorded.
 *   - If only one identifier matches, the settlement record matches that payment.
 *   - A bank-settlement record can match at most one payment.
 *   - Only payments eligible for settlement (APPROVED, CAPTURED, SETTLED, AUTHORIZED)
 *     are classified as MISSING_IN_BANK if absent from the settlement file. PENDING, DECLINED,
 *     FAILED, and INITIATED payments are excluded.
 *   - Payment statuses and ledger records are NEVER modified by this service.
 *   - Duplicate file names are rejected (ConflictException).
 *   - Repeated reconciliation runs on the same file are supported (idempotent re-run).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /**
     * Payments with these statuses represent successful or authorized transactions
     * expecting settlement from the acquiring bank.
     */
    public static final Set<PaymentStatus> SETTLEMENT_ELIGIBLE_STATUSES = Collections.unmodifiableSet(
            EnumSet.of(PaymentStatus.APPROVED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, PaymentStatus.AUTHORIZED)
    );

    // Canonical header synonyms accepted in place of the canonical names
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("payment reference", "payment reference"),
            Map.entry("payment_reference", "payment reference"),
            Map.entry("payment ref", "payment reference"),
            Map.entry("ref", "payment reference"),
            Map.entry("reference", "payment reference"),
            Map.entry("bank reference", "bank reference"),
            Map.entry("bank_reference", "bank reference"),
            Map.entry("bank ref", "bank reference"),
            Map.entry("amount", "amount"),
            Map.entry("settlement amount", "amount"),
            Map.entry("txn amount", "amount"),
            Map.entry("fee", "fee"),
            Map.entry("settlement fee", "fee"),
            Map.entry("bank fee", "fee"),
            Map.entry("currency", "currency"),
            Map.entry("curr", "currency"),
            Map.entry("transaction date", "transaction date"),
            Map.entry("transaction_date", "transaction date"),
            Map.entry("txn date", "transaction date"),
            Map.entry("date", "transaction date"),
            Map.entry("settlement date", "transaction date")
    );

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    };

    private final SettlementFileRepository settlementFileRepo;
    private final SettlementRecordRepository settlementRecordRepo;
    private final ReconciliationRunRepository reconciliationRunRepo;
    private final ReconciliationResultRepository reconciliationResultRepo;
    private final PaymentRepository paymentRepo;
    private final SwitchDatabaseRepository switchDatabaseRepo;

    @Value("${payops.reconciliation.max-file-size:5242880}")
    private long maxFileSize;

    @Value("${payops.reconciliation.max-rows:5000}")
    private int maxRows;

    public ReconciliationService(
            SettlementFileRepository settlementFileRepo,
            SettlementRecordRepository settlementRecordRepo,
            ReconciliationRunRepository reconciliationRunRepo,
            ReconciliationResultRepository reconciliationResultRepo,
            PaymentRepository paymentRepo,
            SwitchDatabaseRepository switchDatabaseRepo) {
        this.settlementFileRepo = settlementFileRepo;
        this.settlementRecordRepo = settlementRecordRepo;
        this.reconciliationRunRepo = reconciliationRunRepo;
        this.reconciliationResultRepo = reconciliationResultRepo;
        this.paymentRepo = paymentRepo;
        this.switchDatabaseRepo = switchDatabaseRepo;
    }

    // -----------------------------------------------------------------------
    // 1. CSV Upload and Parsing
    // -----------------------------------------------------------------------

    /**
     * Validates and persists a settlement CSV file together with its parsed records.
     * Rejects empty files, oversized files, row-count violations, malformed rows,
     * missing headers, invalid data types, and duplicate bank rows.
     * Rejects duplicate file names (ConflictException).
     * The entire operation is transactional – no partial rows are persisted on failure.
     */
    @Transactional
    public SettlementFile uploadAndProcessCsv(MultipartFile file, Long switchId, LocalDate settlementDate) {
        // ---- Guard: null/empty file
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Settlement CSV file must not be empty");
        }

        // ---- Guard: file size
        if (file.getSize() > maxFileSize) {
            throw new BusinessRuleException(
                    "Settlement CSV file exceeds maximum allowed size of " + maxFileSize + " bytes");
        }

        // ---- Guard: file name
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = "settlement-" + System.currentTimeMillis() + ".csv";
        }
        final String fileName = originalFileName;

        // ---- Guard: duplicate file name
        if (settlementFileRepo.findByFileName(fileName).isPresent()) {
            throw new ConflictException("A settlement file with name '" + fileName + "' has already been uploaded");
        }

        // ---- Guard: switch database
        if (switchId == null) {
            throw new BusinessRuleException("switchId is required");
        }
        SwitchDatabase switchDatabase = switchDatabaseRepo.findById(switchId)
                .orElseThrow(() -> new ResourceNotFoundException("SwitchDatabase", switchId));

        LocalDate effectiveSettlementDate = (settlementDate != null) ? settlementDate : LocalDate.now();

        // ---- Parse CSV using RFC 4180 parser
        List<ParsedRow> parsedRows = parseCsv(file, fileName, effectiveSettlementDate);

        // ---- Guard: row count
        if (parsedRows.size() > maxRows) {
            throw new BusinessRuleException(
                    "Settlement CSV contains " + parsedRows.size() + " rows which exceeds maximum of " + maxRows);
        }

        // ---- Guard: duplicate references within the file
        Set<String> seenPayRefs = new HashSet<>();
        Set<String> seenBankRefs = new HashSet<>();
        for (ParsedRow row : parsedRows) {
            if (row.paymentReference != null && !row.paymentReference.isBlank()) {
                if (!seenPayRefs.add(row.paymentReference)) {
                    throw new BusinessRuleException(
                            "Duplicate payment reference '" + row.paymentReference + "' found in CSV at line " + row.lineNumber);
                }
            }
            if (row.bankReference != null && !row.bankReference.isBlank()) {
                if (!seenBankRefs.add(row.bankReference)) {
                    throw new BusinessRuleException(
                            "Duplicate bank reference '" + row.bankReference + "' found in CSV at line " + row.lineNumber);
                }
            }
        }

        // ---- Build SettlementFile entity
        SettlementFile settlementFile = new SettlementFile();
        settlementFile.setFileName(fileName);
        settlementFile.setSwitchDatabase(switchDatabase);
        settlementFile.setSettlementDate(effectiveSettlementDate);
        settlementFile.setStatus(SettlementFileStatus.RECEIVED);
        settlementFile.setRecordCount(0);
        settlementFile.setTotalAmount(BigDecimal.ZERO);
        settlementFile.setReceivedAt(LocalDateTime.now());
        settlementFile = settlementFileRepo.save(settlementFile);

        // ---- Build and save SettlementRecord entities preserving both identifiers
        List<SettlementRecord> records = new ArrayList<>(parsedRows.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ParsedRow row : parsedRows) {
            SettlementRecord record = new SettlementRecord();
            record.setSettlementFile(settlementFile);
            record.setPaymentReference(row.paymentReference);
            record.setBankReference(row.bankReference);
            record.setAmount(row.amount);
            record.setFee(row.fee);
            record.setCurrency(row.currency.toUpperCase());
            record.setTransactionDate(row.transactionDate);
            records.add(record);
            totalAmount = totalAmount.add(row.amount);
        }
        settlementRecordRepo.saveAll(records);

        // ---- Update file totals
        settlementFile.setRecordCount(records.size());
        settlementFile.setTotalAmount(totalAmount);
        settlementFile.setStatus(SettlementFileStatus.PARSED);
        settlementFile = settlementFileRepo.save(settlementFile);

        log.info("Uploaded settlement file '{}' with {} records, total amount {}",
                fileName, records.size(), totalAmount);
        return settlementFile;
    }

    // -----------------------------------------------------------------------
    // 2. Reconciliation Run
    // -----------------------------------------------------------------------

    /**
     * Matches all records in the given settlement file against the main-switch payments
     * and persists a complete ReconciliationRun with all ReconciliationResult rows.
     * <p>
     * Matching rules:
     *   - Both {@code paymentReference} and {@code bankReference} on the settlement record
     *     are checked against {@code Payment.reference} and {@code Payment.bankReference}.
     *   - If both identifiers resolve to the SAME payment, it is treated as a single match.
     *   - If both identifiers resolve to DIFFERENT payments, a {@link ResultType#DUPLICATE}
     *     discrepancy is recorded and neither payment is silently matched.
     *   - If only one identifier matches, the record matches that payment.
     *   - If an identifier resolves to a payment that was ALREADY matched by an earlier record
     *     in this run, it is classified as a {@link ResultType#DUPLICATE} discrepancy.
     *   - If identifier matches AND amount AND currency agree → {@link ResultType#MATCHED}.
     *   - If identifier matches but amount or currency differs → {@link ResultType#AMOUNT_MISMATCH}.
     *   - A bank record that matches no payment → {@link ResultType#MISSING_IN_PAYMENTS}.
     *   - An eligible payment (APPROVED, CAPTURED, SETTLED, AUTHORIZED) scoped to the switch
     *     that is matched by no bank record → {@link ResultType#MISSING_IN_BANK}.
     *     Ineligible payments (PENDING, DECLINED, FAILED, INITIATED) are excluded.
     * <p>
     * Invariants:
     *   - This method does NOT alter payment statuses or create ledger postings.
     *   - If processing fails at any point, the entire run is rolled back (no partial results).
     */
    @Transactional
    public ReconciliationRun runReconciliation(Long settlementFileId) {
        SettlementFile settlementFile = settlementFileRepo.findById(settlementFileId)
                .orElseThrow(() -> new ResourceNotFoundException("SettlementFile", settlementFileId));

        List<SettlementRecord> bankRecords = settlementRecordRepo.findBySettlementFileId(settlementFileId);
        if (bankRecords.isEmpty()) {
            throw new BusinessRuleException("Settlement file " + settlementFileId + " has no records to reconcile");
        }

        // Load all payments for the switch to scope the comparison
        Long switchId = settlementFile.getSwitchDatabase().getId();
        List<Payment> payments = paymentRepo.findBySwitchDatabaseId(switchId);

        // Build lookup maps for fast matching
        Map<String, Payment> byReference = new LinkedHashMap<>();
        Map<String, Payment> byBankRef = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.getReference() != null && !p.getReference().isBlank()) {
                byReference.putIfAbsent(p.getReference(), p);
            }
            if (p.getBankReference() != null && !p.getBankReference().isBlank()) {
                byBankRef.putIfAbsent(p.getBankReference(), p);
            }
        }

        // Track matched payments and settlement records to prevent duplicate matches
        Set<Long> matchedPaymentIds = new HashSet<>();
        Set<Long> matchedRecordIds = new HashSet<>();
        Map<Long, Long> paymentToFirstRecordId = new HashMap<>();

        // Create and save the run in RUNNING state
        String runNumber = "RUN-" + settlementFileId + "-" + System.currentTimeMillis();
        ReconciliationRun run = new ReconciliationRun();
        run.setRunNumber(runNumber);
        run.setSettlementFile(settlementFile);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setTotalChecked(0);
        run.setMatchedCount(0);
        run.setExceptionCount(0);
        run = reconciliationRunRepo.save(run);

        List<ReconciliationResult> results = new ArrayList<>();

        // ---- Match bank records to payments
        for (SettlementRecord record : bankRecords) {
            String payRef = record.getPaymentReference();
            String bankRef = record.getBankReference();

            // Resolve via payment reference (against Payment.reference then Payment.bankReference)
            Payment paymentFromPayRef = null;
            if (payRef != null && !payRef.isBlank()) {
                paymentFromPayRef = byReference.get(payRef);
                if (paymentFromPayRef == null) {
                    paymentFromPayRef = byBankRef.get(payRef);
                }
            }

            // Resolve via bank reference (against Payment.bankReference then Payment.reference)
            Payment paymentFromBankRef = null;
            if (bankRef != null && !bankRef.isBlank()) {
                paymentFromBankRef = byBankRef.get(bankRef);
                if (paymentFromBankRef == null) {
                    paymentFromBankRef = byReference.get(bankRef);
                }
            }

            ReconciliationResult result = new ReconciliationResult();
            result.setReconciliationRun(run);
            result.setSettlementRecord(record);

            if (paymentFromPayRef == null && paymentFromBankRef == null) {
                // Bank has a record; no payment found in switch
                result.setResultType(ResultType.MISSING_IN_PAYMENTS);
                result.setActualAmount(record.getAmount());
                String refLabel = (payRef != null && !payRef.isBlank() ? payRef : "")
                        + (bankRef != null && !bankRef.isBlank() ? ((payRef != null && !payRef.isBlank() ? " / " : "") + bankRef) : "");
                result.setNote("No payment found for reference(s): " + refLabel);
            } else if (paymentFromPayRef != null && paymentFromBankRef != null
                    && !paymentFromPayRef.getId().equals(paymentFromBankRef.getId())) {
                // Conflicting references: payRef and bankRef resolved to different payments!
                result.setResultType(ResultType.DUPLICATE);
                result.setActualAmount(record.getAmount());
                result.setNote("Conflicting references: payment reference '" + payRef + "' resolved to payment "
                        + paymentFromPayRef.getReference() + " (id=" + paymentFromPayRef.getId()
                        + ") but bank reference '" + bankRef + "' resolved to payment "
                        + paymentFromBankRef.getReference() + " (id=" + paymentFromBankRef.getId() + ")");
            } else {
                // Exactly one resolved payment, or both resolve to the same payment
                Payment matched = (paymentFromPayRef != null) ? paymentFromPayRef : paymentFromBankRef;

                if (matchedPaymentIds.contains(matched.getId())) {
                    // Another record in this file already matched this payment!
                    Long firstRecId = paymentToFirstRecordId.get(matched.getId());
                    result.setPayment(matched);
                    result.setResultType(ResultType.DUPLICATE);
                    result.setExpectedAmount(matched.getAmount());
                    result.setActualAmount(record.getAmount());
                    result.setNote("Duplicate bank match: payment " + matched.getReference()
                            + " was already matched by record "
                            + (firstRecId != null ? firstRecId : "earlier in this run"));
                } else {
                    // First match for this payment
                    matchedPaymentIds.add(matched.getId());
                    paymentToFirstRecordId.put(matched.getId(), record.getId());
                    result.setPayment(matched);

                    boolean amountMatches = record.getAmount().compareTo(matched.getAmount()) == 0;
                    boolean currencyMatches = record.getCurrency().equalsIgnoreCase(matched.getCurrency());

                    if (amountMatches && currencyMatches) {
                        result.setResultType(ResultType.MATCHED);
                        result.setExpectedAmount(matched.getAmount());
                        result.setActualAmount(record.getAmount());
                        if (record.getId() != null) {
                            matchedRecordIds.add(record.getId());
                        }
                    } else {
                        result.setResultType(ResultType.AMOUNT_MISMATCH);
                        result.setExpectedAmount(matched.getAmount());
                        result.setActualAmount(record.getAmount());
                        if (!currencyMatches) {
                            result.setNote("Currency mismatch: payment=" + matched.getCurrency()
                                    + " bank=" + record.getCurrency());
                        } else {
                            result.setNote("Amount mismatch: expected " + matched.getAmount()
                                    + " but bank reported " + record.getAmount());
                        }
                    }
                }
            }
            results.add(result);
        }

        // ---- Find payments with no matching bank record → MISSING_IN_BANK
        // Invariant: only payments eligible for settlement (APPROVED, CAPTURED, SETTLED, AUTHORIZED)
        // are reported. PENDING, DECLINED, FAILED, and INITIATED are excluded.
        for (Payment p : payments) {
            if (!matchedPaymentIds.contains(p.getId())) {
                if (SETTLEMENT_ELIGIBLE_STATUSES.contains(p.getStatus())) {
                    ReconciliationResult result = new ReconciliationResult();
                    result.setReconciliationRun(run);
                    result.setPayment(p);
                    result.setResultType(ResultType.MISSING_IN_BANK);
                    result.setExpectedAmount(p.getAmount());
                    result.setNote("Payment reference " + p.getReference() + " with status "
                            + p.getStatus() + " not present in bank settlement");
                    results.add(result);
                } else {
                    log.debug("Payment {} with status {} is ineligible for settlement reconciliation; excluding from MISSING_IN_BANK",
                            p.getReference(), p.getStatus());
                }
            }
        }

        // ---- Persist results
        reconciliationResultRepo.saveAll(results);

        // ---- Update run summary
        long matched = results.stream().filter(r -> r.getResultType() == ResultType.MATCHED).count();
        long exceptions = results.size() - matched;

        run.setTotalChecked(results.size());
        run.setMatchedCount((int) matched);
        run.setExceptionCount((int) exceptions);
        run.setStatus(RunStatus.COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run = reconciliationRunRepo.save(run);

        // ---- Mark settlement file as reconciled
        settlementFile.setStatus(SettlementFileStatus.RECONCILED);
        settlementFileRepo.save(settlementFile);

        log.info("Reconciliation run {} completed: {} results ({} matched, {} exceptions)",
                runNumber, results.size(), matched, exceptions);
        return run;
    }

    // -----------------------------------------------------------------------
    // 3. CSV Export
    // -----------------------------------------------------------------------

    /**
     * Exports reconciliation results for a given run as an RFC 4180 CSV byte array.
     * Columns: Result ID, Result Type, Payment Reference, Expected Amount, Actual Amount, Note.
     * All string fields are properly escaped against quotes, commas, and newlines.
     * No sensitive card data is included.
     */
    public byte[] exportResultsCsv(Long runId) {
        ReconciliationRun run = reconciliationRunRepo.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("ReconciliationRun", runId));
        List<ReconciliationResult> results = reconciliationResultRepo.findByReconciliationRunId(runId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            pw.println("Result ID,Result Type,Payment Reference,Expected Amount,Actual Amount,Note");
            for (ReconciliationResult r : results) {
                String payRef = "";
                if (r.getPayment() != null && r.getPayment().getReference() != null) {
                    payRef = r.getPayment().getReference();
                } else if (r.getSettlementRecord() != null) {
                    if (r.getSettlementRecord().getPaymentReference() != null && !r.getSettlementRecord().getPaymentReference().isBlank()) {
                        payRef = r.getSettlementRecord().getPaymentReference();
                    } else if (r.getSettlementRecord().getBankReference() != null) {
                        payRef = r.getSettlementRecord().getBankReference();
                    }
                }
                pw.printf("%d,%s,%s,%s,%s,%s%n",
                        r.getId() != null ? r.getId() : 0L,
                        r.getResultType().name(),
                        CsvParser.escape(payRef),
                        r.getExpectedAmount() != null ? r.getExpectedAmount().toPlainString() : "",
                        r.getActualAmount() != null ? r.getActualAmount().toPlainString() : "",
                        CsvParser.escape(r.getNote() != null ? r.getNote() : "")
                );
            }
        }
        return baos.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Internal CSV parsing using CsvParser
    // -----------------------------------------------------------------------

    private List<ParsedRow> parseCsv(MultipartFile file, String fileName, LocalDate defaultDate) {
        List<List<String>> records;
        try {
            records = CsvParser.parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BusinessRuleException("Failed to read settlement CSV file: " + e.getMessage());
        }

        if (records.isEmpty()) {
            throw new BusinessRuleException("Settlement CSV file '" + fileName + "' is empty or has no header row");
        }

        // Header row
        List<String> rawHeaders = records.get(0);
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int i = 0; i < rawHeaders.size(); i++) {
            String h = rawHeaders.get(i).trim().toLowerCase().replaceAll("[\"']", "");
            String canonical = HEADER_ALIASES.getOrDefault(h, h);
            headerIndex.put(canonical, i);
        }

        // Validate identifier header
        boolean hasPayRef = headerIndex.containsKey("payment reference");
        boolean hasBankRef = headerIndex.containsKey("bank reference");
        if (!hasPayRef && !hasBankRef) {
            throw new BusinessRuleException(
                    "Settlement CSV is missing required identifier header. Must contain 'payment reference' or 'bank reference'");
        }

        // Validate amount header
        if (!headerIndex.containsKey("amount")) {
            throw new BusinessRuleException("Settlement CSV is missing required header: 'amount'");
        }

        int payRefIdx = hasPayRef ? headerIndex.get("payment reference") : -1;
        int bankRefIdx = hasBankRef ? headerIndex.get("bank reference") : -1;
        int amountIdx = headerIndex.get("amount");
        Integer feeIdx = headerIndex.get("fee");
        Integer currencyIdx = headerIndex.get("currency");
        Integer dateIdx = headerIndex.get("transaction date");

        List<ParsedRow> rows = new ArrayList<>(records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            int lineNumber = i + 1;
            List<String> cols = records.get(i);
            if (cols.isEmpty() || (cols.size() == 1 && cols.get(0).isBlank())) {
                continue;
            }

            // Identifiers: extract both if present
            String payRef = (payRefIdx >= 0) ? getColumn(cols, payRefIdx) : "";
            String bankRef = (bankRefIdx >= 0) ? getColumn(cols, bankRefIdx) : "";

            if (payRef.isEmpty() && bankRef.isEmpty()) {
                throw new BusinessRuleException("Line " + lineNumber + ": payment reference or bank reference must not be blank");
            }
            if (!payRef.isEmpty() && payRef.length() > 50) {
                throw new BusinessRuleException("Line " + lineNumber + ": payment reference exceeds 50 characters");
            }
            if (!bankRef.isEmpty() && bankRef.length() > 100) {
                throw new BusinessRuleException("Line " + lineNumber + ": bank reference exceeds 100 characters");
            }

            // Amount
            String amountStr = getColumn(cols, amountIdx);
            if (amountStr.isEmpty()) {
                throw new BusinessRuleException("Line " + lineNumber + ": amount must not be blank");
            }
            BigDecimal amount = parseBigDecimal(amountStr, lineNumber, "amount");
            if (amount.signum() <= 0) {
                throw new BusinessRuleException("Line " + lineNumber + ": amount must be greater than zero, got: " + amount);
            }

            // Fee (optional, defaults to 0.00)
            BigDecimal fee = BigDecimal.ZERO;
            if (feeIdx != null) {
                String feeStr = getColumn(cols, feeIdx);
                if (!feeStr.isEmpty()) {
                    fee = parseBigDecimal(feeStr, lineNumber, "fee");
                    if (fee.signum() < 0) {
                        throw new BusinessRuleException("Line " + lineNumber + ": fee must not be negative, got: " + fee);
                    }
                    if (fee.compareTo(amount) > 0) {
                        throw new BusinessRuleException("Line " + lineNumber + ": fee (" + fee + ") must not exceed amount (" + amount + ")");
                    }
                }
            }

            // Currency (optional, defaults to INR)
            String currency = "INR";
            if (currencyIdx != null) {
                String currStr = getColumn(cols, currencyIdx);
                if (!currStr.isEmpty()) {
                    currency = currStr.toUpperCase();
                    if (currency.length() != 3) {
                        throw new BusinessRuleException("Line " + lineNumber + ": currency must be a 3-letter code, got: " + currency);
                    }
                }
            }

            // Transaction date (optional, defaults to defaultDate at start of day)
            LocalDateTime transactionDate = defaultDate.atStartOfDay();
            if (dateIdx != null) {
                String dateStr = getColumn(cols, dateIdx);
                if (!dateStr.isEmpty()) {
                    transactionDate = parseDateTime(dateStr, lineNumber);
                }
            }

            rows.add(new ParsedRow(lineNumber,
                    payRef.isEmpty() ? null : payRef,
                    bankRef.isEmpty() ? null : bankRef,
                    amount, fee, currency, transactionDate));
        }

        return rows;
    }

    private String getColumn(List<String> cols, int idx) {
        if (idx < 0 || idx >= cols.size()) {
            return "";
        }
        return cols.get(idx).trim();
    }

    private BigDecimal parseBigDecimal(String raw, int lineNumber, String fieldName) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new BusinessRuleException(
                    "Line " + lineNumber + ": '" + fieldName + "' is not a valid decimal number: " + raw);
        }
    }

    private LocalDateTime parseDateTime(String raw, int lineNumber) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                try {
                    return LocalDateTime.parse(raw, fmt);
                } catch (DateTimeParseException ignored) {
                    return LocalDate.parse(raw, fmt).atStartOfDay();
                }
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        throw new BusinessRuleException(
                "Line " + lineNumber + ": 'transaction date' has unrecognised format: " + raw);
    }

    /** Immutable value holder for one parsed CSV row. */
    private static final class ParsedRow {
        final int lineNumber;
        final String paymentReference;
        final String bankReference;
        final BigDecimal amount;
        final BigDecimal fee;
        final String currency;
        final LocalDateTime transactionDate;

        ParsedRow(int lineNumber, String paymentReference, String bankReference, BigDecimal amount,
                  BigDecimal fee, String currency, LocalDateTime transactionDate) {
            this.lineNumber = lineNumber;
            this.paymentReference = paymentReference;
            this.bankReference = bankReference;
            this.amount = amount;
            this.fee = fee;
            this.currency = currency;
            this.transactionDate = transactionDate;
        }
    }
}
