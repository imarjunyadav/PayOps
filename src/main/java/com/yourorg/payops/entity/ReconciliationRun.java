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

/** One execution of the comparison between a SettlementFile and Payments. */
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 40)
    @Column(name = "run_number", nullable = false, length = 40, unique = true)
    private String runNumber;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_file_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SettlementFile settlementFile;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private RunStatus status = RunStatus.RUNNING;
    @NotNull
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();
    @Column(name = "completed_at", nullable = true)
    private LocalDateTime completedAt;
    @NotNull
    @PositiveOrZero
    @Column(name = "total_checked", nullable = false)
    private Integer totalChecked = 0;
    @NotNull
    @PositiveOrZero
    @Column(name = "matched_count", nullable = false)
    private Integer matchedCount = 0;
    @NotNull
    @PositiveOrZero
    @Column(name = "exception_count", nullable = false)
    private Integer exceptionCount = 0;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getRunNumber() { return runNumber; }
    public void setRunNumber(String v) { this.runNumber = v; }
    public SettlementFile getSettlementFile() { return settlementFile; }
    public void setSettlementFile(SettlementFile v) { this.settlementFile = v; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus v) { this.status = v; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime v) { this.startedAt = v; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }
    public Integer getTotalChecked() { return totalChecked; }
    public void setTotalChecked(Integer v) { this.totalChecked = v; }
    public Integer getMatchedCount() { return matchedCount; }
    public void setMatchedCount(Integer v) { this.matchedCount = v; }
    public Integer getExceptionCount() { return exceptionCount; }
    public void setExceptionCount(Integer v) { this.exceptionCount = v; }
}
