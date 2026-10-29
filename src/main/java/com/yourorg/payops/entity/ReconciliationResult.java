package com.yourorg.payops.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yourorg.payops.model.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** One outcome row of a run for one settlement record / payment. */
@Entity
@Table(name = "reconciliation_result")
public class ReconciliationResult extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_run_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private ReconciliationRun reconciliationRun;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "settlement_record_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SettlementRecord settlementRecord;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "payment_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Payment payment;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "result_type", nullable = false, length = 30)
    private ResultType resultType;
    @Column(name = "expected_amount", nullable = true, precision = 19, scale = 4)
    private BigDecimal expectedAmount;
    @Column(name = "actual_amount", nullable = true, precision = 19, scale = 4)
    private BigDecimal actualAmount;
    @Size(max = 255)
    @Column(name = "note", nullable = true, length = 255)
    private String note;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public ReconciliationRun getReconciliationRun() { return reconciliationRun; }
    public void setReconciliationRun(ReconciliationRun v) { this.reconciliationRun = v; }
    public SettlementRecord getSettlementRecord() { return settlementRecord; }
    public void setSettlementRecord(SettlementRecord v) { this.settlementRecord = v; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment v) { this.payment = v; }
    public ResultType getResultType() { return resultType; }
    public void setResultType(ResultType v) { this.resultType = v; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal v) { this.expectedAmount = v; }
    public BigDecimal getActualAmount() { return actualAmount; }
    public void setActualAmount(BigDecimal v) { this.actualAmount = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
