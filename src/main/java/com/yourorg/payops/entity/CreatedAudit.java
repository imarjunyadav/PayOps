package com.yourorg.payops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Not a table. Adds created_at / created_by to child entities. */
@MappedSuperclass
public abstract class CreatedAudit {
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy; // username String (see TEAM_CONTRACT decision D1); set by controller

    @PrePersist
    protected void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
}
