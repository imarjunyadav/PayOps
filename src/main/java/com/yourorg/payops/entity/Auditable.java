package com.yourorg.payops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Not a table. created_at/created_by + updated_at. */
@MappedSuperclass
public abstract class Auditable extends CreatedAudit {
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() { updatedAt = LocalDateTime.now(); }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
