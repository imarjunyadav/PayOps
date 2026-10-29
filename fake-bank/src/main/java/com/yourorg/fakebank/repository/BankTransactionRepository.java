package com.yourorg.fakebank.repository;

import com.yourorg.fakebank.entity.BankTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    @Query("SELECT t FROM BankTransaction t LEFT JOIN FETCH t.card WHERE t.requestId = :requestId")
    Optional<BankTransaction> findByRequestId(@Param("requestId") String requestId);

    @Query("SELECT t FROM BankTransaction t LEFT JOIN FETCH t.card WHERE t.paymentReference = :paymentReference")
    Optional<BankTransaction> findByPaymentReference(@Param("paymentReference") String paymentReference);

    @Query("SELECT t FROM BankTransaction t LEFT JOIN FETCH t.card WHERE t.bankReference = :bankReference")
    Optional<BankTransaction> findByBankReference(@Param("bankReference") String bankReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BankTransaction t LEFT JOIN FETCH t.card WHERE t.id = :id")
    Optional<BankTransaction> findByIdForUpdate(@Param("id") Long id);
}
