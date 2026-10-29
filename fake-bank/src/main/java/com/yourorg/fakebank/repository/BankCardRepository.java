package com.yourorg.fakebank.repository;

import com.yourorg.fakebank.entity.BankCard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BankCardRepository extends JpaRepository<BankCard, Long> {

    Optional<BankCard> findByCardNumber(String cardNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM BankCard c WHERE c.cardNumber = :cardNumber")
    Optional<BankCard> findByCardNumberForUpdate(@Param("cardNumber") String cardNumber);
}
