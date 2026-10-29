package com.yourorg.payops.repository;

import com.yourorg.payops.entity.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostingRepository extends JpaRepository<Posting, Long> {
    @Query("SELECT p FROM Posting p JOIN FETCH p.account WHERE p.journalEntry.id = :journalEntryId")
    List<Posting> findByJournalEntryId(@Param("journalEntryId") Long journalEntryId);

    List<Posting> findByAccountId(Long accountId);
}
