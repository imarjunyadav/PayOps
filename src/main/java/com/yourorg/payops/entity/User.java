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

/** A person who logs in to PayOps. Table is app_user because "user" is reserved in PostgreSQL. */
@Entity
@Table(name = "app_user")
public class User extends Auditable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @NotBlank
    @Size(max = 50)
    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;
    @NotBlank
    @Size(max = 100)
    @Column(name = "email", nullable = false, length = 100, unique = true)
    private String email;
    @NotBlank
    @Size(max = 100)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Size(max = 100)
    @Column(name = "full_name", nullable = true, length = 100)
    private String fullName;
    @Enumerated(EnumType.STRING)
    @NotNull
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status = UserStatus.ACTIVE;
    @Column(name = "last_login_at", nullable = true)
    private LocalDateTime lastLoginAt;
    @ManyToOne(fetch = FetchType.EAGER, optional = true)
    @JoinColumn(name = "merchant_id", nullable = true)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Merchant merchant;
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus v) { this.status = v; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime v) { this.lastLoginAt = v; }
    public Merchant getMerchant() { return merchant; }
    public void setMerchant(Merchant v) { this.merchant = v; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> v) { this.roles = v; }
}
