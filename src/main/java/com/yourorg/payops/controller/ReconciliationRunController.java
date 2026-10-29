package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.ReconciliationResult;
import com.yourorg.payops.entity.ReconciliationRun;
import com.yourorg.payops.entity.SettlementFile;
import com.yourorg.payops.entity.SettlementRecord;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.ResultType;
import com.yourorg.payops.model.RunStatus;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.ReconciliationResultRepository;
import com.yourorg.payops.repository.ReconciliationRunRepository;
import com.yourorg.payops.repository.SettlementFileRepository;
import com.yourorg.payops.repository.SettlementRecordRepository;
import com.yourorg.payops.service.ReconciliationService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/reconciliation-runs")
public class ReconciliationRunController {
    private final ReconciliationRunRepository runs;
    private final ReconciliationResultRepository results;
    private final SettlementFileRepository files;
    private final SettlementRecordRepository settlementRecords;
    private final PaymentRepository payments;
    private final Validator validator;
    private final ReconciliationService reconciliationService;

    public ReconciliationRunController(ReconciliationRunRepository runs,
            ReconciliationResultRepository results, SettlementFileRepository files,
            SettlementRecordRepository settlementRecords, PaymentRepository payments,
            Validator validator, ReconciliationService reconciliationService) {
        this.runs = runs;
        this.results = results;
        this.files = files;
        this.settlementRecords = settlementRecords;
        this.payments = payments;
        this.validator = validator;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public List<ReconciliationRun> list() {
        return runs.findAll();
    }

    @GetMapping("/{id}")
    public ReconciliationRun get(@PathVariable Long id) {
        return findRun(id);
    }

    @PostMapping
    public ResponseEntity<ReconciliationRun> create(@jakarta.validation.Valid @RequestBody ReconciliationRun body) {
        if (runs.findByRunNumber(body.getRunNumber()).isPresent()) {
            throw new ConflictException("Reconciliation run number already exists");
        }
        SettlementFile settlementFile = resolveSettlementFile(body.getSettlementFile());
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setSettlementFile(settlementFile);
        body.setStatus(RunStatus.RUNNING);
        body.setStartedAt(LocalDateTime.now());
        body.setCompletedAt(null);
        body.setTotalChecked(0);
        body.setMatchedCount(0);
        body.setExceptionCount(0);

        ReconciliationRun saved = runs.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PostMapping("/{id}/results")
    @Transactional
    public ResponseEntity<List<ReconciliationResult>> addResults(@PathVariable Long id,
            @RequestBody List<ReconciliationResult> body) {
        ReconciliationRun run = findRun(id);
        if (body == null || body.isEmpty()) {
            throw new BusinessRuleException("At least one reconciliation result is required");
        }

        List<ReconciliationResult> toSave = new ArrayList<>(body.size());
        int matched = 0;
        int exceptions = 0;
        for (ReconciliationResult result : body) {
            if (result == null) throw new BusinessRuleException("Reconciliation result is required");
            Payment payment = resolvePayment(result.getPayment());
            SettlementRecord settlementRecord = resolveSettlementRecord(result.getSettlementRecord());
            if (payment == null && settlementRecord == null) {
                throw new BusinessRuleException("A payment or settlement record reference is required");
            }
            if (settlementRecord != null) {
                Long recordFileId = settlementRecord.getSettlementFile() == null
                        ? null : settlementRecord.getSettlementFile().getId();
                Long runFileId = run.getSettlementFile() == null ? null : run.getSettlementFile().getId();
                if (recordFileId == null || !recordFileId.equals(runFileId)) {
                    throw new BusinessRuleException("Settlement record must belong to the run's settlement file");
                }
            }

            result.setId(null);
            result.setCreatedAt(null);
            result.setCreatedBy(null);
            result.setReconciliationRun(run);
            result.setPayment(payment);
            result.setSettlementRecord(settlementRecord);
            if (payment != null && settlementRecord != null) {
                result.setExpectedAmount(payment.getAmount());
                result.setActualAmount(settlementRecord.getAmount());
                result.setResultType(payment.getAmount().compareTo(settlementRecord.getAmount()) == 0
                        ? ResultType.MATCHED : ResultType.AMOUNT_MISMATCH);
            } else if (payment != null) {
                result.setExpectedAmount(payment.getAmount());
                result.setActualAmount(null);
                result.setResultType(ResultType.MISSING_IN_SETTLEMENT);
            } else {
                result.setExpectedAmount(null);
                result.setActualAmount(settlementRecord.getAmount());
                result.setResultType(ResultType.MISSING_IN_PAYMENTS);
            }

            var violations = validator.validate(result);
            if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            if (result.getResultType() == ResultType.MATCHED) matched++;
            else exceptions++;
            toSave.add(result);
        }

        List<ReconciliationResult> saved = results.saveAll(toSave);
        run.setTotalChecked(run.getTotalChecked() + saved.size());
        run.setMatchedCount(run.getMatchedCount() + matched);
        run.setExceptionCount(run.getExceptionCount() + exceptions);
        runs.save(run);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping("/{id}/results")
    public List<ReconciliationResult> listResults(@PathVariable Long id) {
        findRun(id);
        return results.findByReconciliationRunId(id);
    }

    private ReconciliationRun findRun(Long id) {
        return runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("ReconciliationRun", id));
    }

    /**
     * Trigger a new automated reconciliation run for a settlement file.
     * POST /api/reconciliation-runs/trigger?settlementFileId={id}
     *
     * Parses existing settlement records, matches them to payments for the file's switch,
     * and persists a complete ReconciliationRun with all results.
     * Does NOT alter payment statuses or create ledger postings.
     */
    @PostMapping("/trigger")
    public ResponseEntity<ReconciliationRun> trigger(
            @RequestParam("settlementFileId") Long settlementFileId) {
        ReconciliationRun run = reconciliationService.runReconciliation(settlementFileId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/reconciliation-runs/{id}")
                .buildAndExpand(run.getId()).toUri();
        return ResponseEntity.created(location).body(run);
    }

    /**
     * Download reconciliation results as a CSV file.
     * GET /api/reconciliation-runs/{id}/export
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        // Verify run exists (throws 404 if missing)
        findRun(id);
        byte[] csvBytes = reconciliationService.exportResultsCsv(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("reconciliation-run-" + id + ".csv")
                        .build());
        return ResponseEntity.ok().headers(headers).body(csvBytes);
    }


    private SettlementFile resolveSettlementFile(SettlementFile supplied) {
        Long fileId = supplied == null ? null : supplied.getId();
        if (fileId == null) throw new ResourceNotFoundException("SettlementFile", null);
        return files.findById(fileId).orElseThrow(() -> new ResourceNotFoundException("SettlementFile", fileId));
    }

    private Payment resolvePayment(Payment supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("Payment", null);
        return payments.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", supplied.getId()));
    }

    private SettlementRecord resolveSettlementRecord(SettlementRecord supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("SettlementRecord", null);
        return settlementRecords.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("SettlementRecord", supplied.getId()));
    }
}
