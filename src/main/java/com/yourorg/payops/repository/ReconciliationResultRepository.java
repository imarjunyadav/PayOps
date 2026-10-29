package com.yourorg.payops.repository;

import com.yourorg.payops.entity.ReconciliationResult;
import com.yourorg.payops.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {
    List<ReconciliationResult> findByReconciliationRunId(Long runId);
    List<ReconciliationResult> findByResultType(ResultType resultType);
}
