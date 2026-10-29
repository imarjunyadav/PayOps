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

/** Append-only audit row written on every Payment state change. */
@Entity
@Table(name = "payment_event")
public class PaymentEvent extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Payment payment;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "event_type", nullable = false, length = 30)
    private PaymentStatus eventType;
    @Column(name = "amount", nullable = true, precision = 19, scale = 4)
    private BigDecimal amount;
    @Size(max = 255)
    @Column(name = "message", nullable = true, length = 255)
    private String message;
    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment v) { this.payment = v; }
    public PaymentStatus getEventType() { return eventType; }
    public void setEventType(PaymentStatus v) { this.eventType = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime v) { this.occurredAt = v; }
}
