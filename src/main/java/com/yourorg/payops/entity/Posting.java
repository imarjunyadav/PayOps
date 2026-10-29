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

/** One debit or credit line of a JournalEntry against an Account. */
@Entity
@Table(name = "posting")
public class Posting extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private JournalEntry journalEntry;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Account account;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "direction", nullable = false, length = 30)
    private PostingDirection direction;
    @NotNull
    @Positive
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Size(max = 255)
    @Column(name = "description", nullable = true, length = 255)
    private String description;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public JournalEntry getJournalEntry() { return journalEntry; }
    public void setJournalEntry(JournalEntry v) { this.journalEntry = v; }
    public Account getAccount() { return account; }
    public void setAccount(Account v) { this.account = v; }
    public PostingDirection getDirection() { return direction; }
    public void setDirection(PostingDirection v) { this.direction = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}
