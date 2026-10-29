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

/** A job title that decides what a person may do (ADMIN, OPS_ENGINEER, FINANCE, SUPPORT). */
@Entity
@Table(name = "role")
public class Role extends CreatedAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "name", nullable = false, length = 30)
    private RoleName name;
    @Size(max = 200)
    @Column(name = "description", nullable = true, length = 200)
    private String description;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public RoleName getName() { return name; }
    public void setName(RoleName v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
}
