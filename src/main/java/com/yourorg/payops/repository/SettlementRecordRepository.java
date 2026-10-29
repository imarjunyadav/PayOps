package com.yourorg.payops.repository;

import com.yourorg.payops.entity.SettlementRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {
    List<SettlementRecord> findBySettlementFileId(Long fileId);
    List<SettlementRecord> findByPaymentReference(String paymentReference);
}
