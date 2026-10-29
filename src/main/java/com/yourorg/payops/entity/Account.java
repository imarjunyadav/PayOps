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

/** A ledger account (chart of accounts) that postings debit or credit. */
@Entity
@Table(name = "account")
public class Account extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 30)
    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;
    @NotBlank
    @Size(max = 100)
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "account_type", nullable = false, length = 30)
    private AccountType accountType;
    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";
    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = true;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "merchant_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Merchant merchant;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType v) { this.accountType = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean v) { this.active = v; }
    public Merchant getMerchant() { return merchant; }
    public void setMerchant(Merchant v) { this.merchant = v; }
}
