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

/** One transaction line inside a SettlementFile. */
@Entity
@Table(name = "settlement_record")
public class SettlementRecord extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_file_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SettlementFile settlementFile;
    @Size(max = 50)
    @Column(name = "payment_reference", nullable = true, length = 50)
    private String paymentReference;
    @Size(max = 100)
    @Column(name = "bank_reference", nullable = true, length = 100)
    private String bankReference;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "payment_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Payment payment;
    @NotNull
    @Positive
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @NotNull
    @PositiveOrZero
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;
    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";
    @Column(name = "transaction_date", nullable = true)
    private LocalDateTime transactionDate;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public SettlementFile getSettlementFile() { return settlementFile; }
    public void setSettlementFile(SettlementFile v) { this.settlementFile = v; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String v) { this.paymentReference = v; }
    public String getBankReference() { return bankReference; }
    public void setBankReference(String v) { this.bankReference = v; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment v) { this.payment = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal v) { this.fee = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDateTime getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDateTime v) { this.transactionDate = v; }
}
