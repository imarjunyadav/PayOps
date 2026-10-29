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

/** One file received from a bank/acquirer listing settled transactions. */
@Entity
@Table(name = "settlement_file")
public class SettlementFile extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 255)
    @Column(name = "file_name", nullable = false, length = 255, unique = true)
    private String fileName;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "switch_database_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SwitchDatabase switchDatabase;
    @NotNull
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private SettlementFileStatus status = SettlementFileStatus.RECEIVED;
    @NotNull
    @PositiveOrZero
    @Column(name = "record_count", nullable = false)
    private Integer recordCount = 0;
    @NotNull
    @PositiveOrZero
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @NotNull
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public SwitchDatabase getSwitchDatabase() { return switchDatabase; }
    public void setSwitchDatabase(SwitchDatabase v) { this.switchDatabase = v; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate v) { this.settlementDate = v; }
    public SettlementFileStatus getStatus() { return status; }
    public void setStatus(SettlementFileStatus v) { this.status = v; }
    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer v) { this.recordCount = v; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal v) { this.totalAmount = v; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime v) { this.receivedAt = v; }
}
