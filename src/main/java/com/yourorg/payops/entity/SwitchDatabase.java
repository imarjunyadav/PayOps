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

/** ASSUMPTION: routing/connection profile of one bank, acquirer or network the transaction switch can send payments to. */
@Entity
@Table(name = "switch_database")
public class SwitchDatabase extends Auditable {
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
    @Column(name = "switch_type", nullable = false, length = 30)
    private SwitchType switchType;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "protocol", nullable = false, length = 30)
    private SwitchProtocol protocol;
    @Size(max = 255)
    @Column(name = "host", nullable = true, length = 255)
    private String host;
    @Min(1)
    @Max(65535)
    @Column(name = "port", nullable = true)
    private Integer port;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private SwitchStatus status = SwitchStatus.ACTIVE;
    @NotNull
    @Positive
    @Column(name = "priority", nullable = false)
    private Integer priority = 1;
    @NotNull
    @Positive
    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs = 30000;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public SwitchType getSwitchType() { return switchType; }
    public void setSwitchType(SwitchType v) { this.switchType = v; }
    public SwitchProtocol getProtocol() { return protocol; }
    public void setProtocol(SwitchProtocol v) { this.protocol = v; }
    public String getHost() { return host; }
    public void setHost(String v) { this.host = v; }
    public Integer getPort() { return port; }
    public void setPort(Integer v) { this.port = v; }
    public SwitchStatus getStatus() { return status; }
    public void setStatus(SwitchStatus v) { this.status = v; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer v) { this.priority = v; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer v) { this.timeoutMs = v; }
}
