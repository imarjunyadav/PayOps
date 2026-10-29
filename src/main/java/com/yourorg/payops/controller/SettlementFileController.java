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
import com.yourorg.payops.service.ReconciliationService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/settlement-files")
public class SettlementFileController {
    private final SettlementFileRepository files;
    private final SettlementRecordRepository records;
    private final SwitchDatabaseRepository switches;
    private final PaymentRepository payments;
    private final ReconciliationService reconciliationService;

    public SettlementFileController(SettlementFileRepository files,
            SettlementRecordRepository records, SwitchDatabaseRepository switches,
            PaymentRepository payments, ReconciliationService reconciliationService) {
        this.files = files;
        this.records = records;
        this.switches = switches;
        this.payments = payments;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public List<SettlementFile> list() {
        return files.findAll();
    }

    @GetMapping("/{id}")
    public SettlementFile get(@PathVariable Long id) {
        return findFile(id);
    }

    @PostMapping
    public ResponseEntity<SettlementFile> create(@Valid @RequestBody SettlementFile body) {
        if (files.findByFileName(body.getFileName()).isPresent()) {
            throw new ConflictException("Settlement file name already exists");
        }
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setUpdatedAt(null);
        body.setSwitchDatabase(resolveSwitch(body.getSwitchDatabase()));
        body.setStatus(SettlementFileStatus.RECEIVED);
        body.setRecordCount(0);
        body.setTotalAmount(BigDecimal.ZERO);
        body.setReceivedAt(LocalDateTime.now());

        SettlementFile saved = files.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @PostMapping("/{id}/records")
    @Transactional
    public ResponseEntity<List<SettlementRecord>> addRecords(@PathVariable Long id,
            @Valid @RequestBody List<@Valid SettlementRecord> body) {
        SettlementFile file = findFile(id);
        if (body == null || body.isEmpty()) {
            throw new BusinessRuleException("At least one settlement record is required");
        }

        List<SettlementRecord> toSave = new ArrayList<>(body.size());
        BigDecimal amountAdded = BigDecimal.ZERO;
        for (SettlementRecord record : body) {
            if (record == null) {
                throw new BusinessRuleException("Settlement record is required");
            }
            if (record.getAmount() == null || record.getAmount().signum() <= 0) {
                throw new BusinessRuleException("Settlement record amount must be greater than zero");
            }
            if (record.getFee() == null || record.getFee().signum() < 0) {
                throw new BusinessRuleException("Settlement record fee must not be negative");
            }
            if (record.getFee().compareTo(record.getAmount()) > 0) {
                throw new BusinessRuleException("Settlement record fee must not exceed its amount");
            }

            Payment payment = resolvePayment(record.getPayment());
            record.setId(null);
            record.setCreatedAt(null);
            record.setCreatedBy(null);
            record.setSettlementFile(file);
            record.setPayment(payment);
            toSave.add(record);
            amountAdded = amountAdded.add(record.getAmount());
        }

        List<SettlementRecord> saved = records.saveAll(toSave);
        file.setRecordCount(file.getRecordCount() + saved.size());
        file.setTotalAmount(file.getTotalAmount().add(amountAdded));
        files.save(file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping("/{id}/records")
    public List<SettlementRecord> listRecords(@PathVariable Long id) {
        findFile(id);
        return records.findBySettlementFileId(id);
    }

    private SettlementFile findFile(Long id) {
        return files.findById(id).orElseThrow(() -> new ResourceNotFoundException("SettlementFile", id));
    }

    /**
     * CSV upload endpoint: POST /api/settlement-files/upload
     * Accepts multipart/form-data with:
     *   - file         (required) – the settlement CSV
     *   - switchId     (required) – ID of the SwitchDatabase that owns this settlement
     *   - settlementDate (optional, ISO yyyy-MM-dd) – defaults to today
     */
    @PostMapping("/upload")
    public ResponseEntity<SettlementFile> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("switchId") Long switchId,
            @RequestParam(value = "settlementDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate) {
        SettlementFile saved = reconciliationService.uploadAndProcessCsv(file, switchId, settlementDate);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/settlement-files/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }


    private SwitchDatabase resolveSwitch(SwitchDatabase supplied) {
        Long switchId = supplied == null ? null : supplied.getId();
        if (switchId == null) throw new ResourceNotFoundException("SwitchDatabase", null);
        return switches.findById(switchId)
                .orElseThrow(() -> new ResourceNotFoundException("SwitchDatabase", switchId));
    }

    private Payment resolvePayment(Payment supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("Payment", null);
        return payments.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", supplied.getId()));
    }
}
