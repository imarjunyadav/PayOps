package com.yourorg.payops.repository;

import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.model.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    boolean existsByEntryNumber(String entryNumber);
    List<JournalEntry> findByPaymentId(Long paymentId);
    boolean existsByPaymentIdAndSource(Long paymentId, JournalSource source);
    Optional<JournalEntry> findByPaymentIdAndSource(Long paymentId, JournalSource source);
}
