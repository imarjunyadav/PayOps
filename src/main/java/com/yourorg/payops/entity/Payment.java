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

/** One customer payment made to a merchant and routed through a switch. Central table. */
@Entity
@Table(name = "payment")
public class Payment extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 50)
    @Column(name = "reference", nullable = false, length = 50, unique = true)
    private String reference;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Merchant merchant;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "switch_database_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SwitchDatabase switchDatabase;
    @NotNull
    @Positive
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @NotNull
    @PositiveOrZero
    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "method", nullable = false, length = 30)
    private PaymentMethod method;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status = PaymentStatus.INITIATED;
    @Size(max = 50)
    @Column(name = "switch_reference", nullable = true, length = 50)
    private String switchReference;
    @Size(max = 100)
    @Column(name = "customer_ref", nullable = true, length = 100)
    private String customerRef;
    @Size(max = 255)
    @Column(name = "failure_reason", nullable = true, length = 255)
    private String failureReason;
    @Size(max = 30)
    @Column(name = "masked_card_number", nullable = true, length = 30)
    private String maskedCardNumber;
    @Size(max = 100)
    @Column(name = "idempotency_key", nullable = true, length = 100)
    private String idempotencyKey;
    @Size(max = 64)
    @Column(name = "request_hash", nullable = true, length = 64)
    private String requestHash;
    @Size(max = 100)
    @Column(name = "bank_request_id", nullable = true, length = 100)
    private String bankRequestId;
    @Size(max = 100)
    @Column(name = "bank_reference", nullable = true, length = 100)
    private String bankReference;
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String cardNumber;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public Merchant getMerchant() { return merchant; }
    public void setMerchant(Merchant v) { this.merchant = v; }
    public SwitchDatabase getSwitchDatabase() { return switchDatabase; }
    public void setSwitchDatabase(SwitchDatabase v) { this.switchDatabase = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal v) { this.refundedAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public PaymentMethod getMethod() { return method; }
    public void setMethod(PaymentMethod v) { this.method = v; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus v) { this.status = v; }
    public String getSwitchReference() { return switchReference; }
    public void setSwitchReference(String v) { this.switchReference = v; }
    public String getCustomerRef() { return customerRef; }
    public void setCustomerRef(String v) { this.customerRef = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public String getMaskedCardNumber() { return maskedCardNumber; }
    public void setMaskedCardNumber(String v) { this.maskedCardNumber = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String v) { this.requestHash = v; }
    public String getBankRequestId() { return bankRequestId; }
    public void setBankRequestId(String v) { this.bankRequestId = v; }
    public String getBankReference() { return bankReference; }
    public void setBankReference(String v) { this.bankReference = v; }
    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String v) { this.cardNumber = v; }
}
