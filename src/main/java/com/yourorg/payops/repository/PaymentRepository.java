package com.yourorg.payops.repository;

import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {
    Optional<Payment> findByReference(String reference);
    List<Payment> findByStatus(PaymentStatus status);
    List<Payment> findByMerchantId(Long merchantId);
    List<Payment> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
    Optional<Payment> findByMerchantIdAndIdempotencyKey(Long merchantId, String idempotencyKey);
    Optional<Payment> findByBankRequestId(String bankRequestId);
    Optional<Payment> findByBankReference(String bankReference);
    List<Payment> findBySwitchDatabaseId(Long switchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    List<Payment> findByStatusAndBankRequestIdIsNotNullOrderByCreatedAtAsc(PaymentStatus status, Pageable pageable);
}
