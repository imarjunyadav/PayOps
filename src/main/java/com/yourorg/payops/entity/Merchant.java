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

/** A business that accepts payments through PayOps. */
@Entity
@Table(name = "merchant")
public class Merchant extends Auditable {
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
    @Size(max = 100)
    @Column(name = "email", nullable = true, length = 100)
    private String email;
    @NotBlank
    @Size(max = 3)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private MerchantStatus status = MerchantStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "default_switch_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SwitchDatabase defaultSwitch;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public MerchantStatus getStatus() { return status; }
    public void setStatus(MerchantStatus v) { this.status = v; }
    public SwitchDatabase getDefaultSwitch() { return defaultSwitch; }
    public void setDefaultSwitch(SwitchDatabase v) { this.defaultSwitch = v; }
}
