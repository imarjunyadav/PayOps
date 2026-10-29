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

/** One accounting event (capture, refund, settlement). Holds balanced Postings. */
@Entity
@Table(name = "journal_entry")
public class JournalEntry extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 40)
    @Column(name = "entry_number", nullable = false, length = 40, unique = true)
    private String entryNumber;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "source", nullable = false, length = 30)
    private JournalSource source;
    @Size(max = 255)
    @Column(name = "description", nullable = true, length = 255)
    private String description;
    @NotNull
    @Column(name = "entry_date", nullable = false)
    private LocalDateTime entryDate = LocalDateTime.now();
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "payment_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Payment payment;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "settlement_record_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SettlementRecord settlementRecord;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getEntryNumber() { return entryNumber; }
    public void setEntryNumber(String v) { this.entryNumber = v; }
    public JournalSource getSource() { return source; }
    public void setSource(JournalSource v) { this.source = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public LocalDateTime getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDateTime v) { this.entryDate = v; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment v) { this.payment = v; }
    public SettlementRecord getSettlementRecord() { return settlementRecord; }
    public void setSettlementRecord(SettlementRecord v) { this.settlementRecord = v; }
}
