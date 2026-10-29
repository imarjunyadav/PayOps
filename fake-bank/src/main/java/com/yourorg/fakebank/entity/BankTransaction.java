package com.yourorg.fakebank.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "request_id", nullable = false, length = 100, unique = true)
    private String requestId;

    @NotBlank
    @Size(max = 50)
    @Column(name = "payment_reference", nullable = false, length = 50, unique = true)
    private String paymentReference;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "card_id")
    private BankCard card;

    @Size(max = 64)
    @Column(name = "card_hash", length = 64)
    private String cardHash;

    @Size(max = 30)
    @Column(name = "masked_card", length = 30)
    private String maskedCard;

    @NotNull
    @Positive
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @NotBlank
    @Size(max = 30)
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @NotBlank
    @Size(max = 100)
    @Column(name = "bank_reference", nullable = false, length = 100, unique = true)
    private String bankReference;

    @Size(max = 30)
    @Column(name = "response_code", length = 30)
    private String responseCode;

    @Size(max = 255)
    @Column(name = "response_message", length = 255)
    private String responseMessage;

    @Column(name = "debited", nullable = false)
    private boolean debited = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public BankTransaction() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public BankCard getCard() { return card; }
    public void setCard(BankCard card) { this.card = card; }

    public String getCardHash() { return cardHash; }
    public void setCardHash(String cardHash) { this.cardHash = cardHash; }

    public String getMaskedCard() { return maskedCard; }
    public void setMaskedCard(String maskedCard) { this.maskedCard = maskedCard; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBankReference() { return bankReference; }
    public void setBankReference(String bankReference) { this.bankReference = bankReference; }

    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }

    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }

    public boolean isDebited() { return debited; }
    public void setDebited(boolean debited) { this.debited = debited; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
