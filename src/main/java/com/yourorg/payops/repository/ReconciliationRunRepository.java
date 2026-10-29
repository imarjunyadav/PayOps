package com.yourorg.payops.repository;

import com.yourorg.payops.entity.ReconciliationRun;
import com.yourorg.payops.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {
    Optional<ReconciliationRun> findByRunNumber(String runNumber);
    List<ReconciliationRun> findBySettlementFileId(Long settlementFileId);
}
