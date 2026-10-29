package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.PostingRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/postings")
public class PostingController {

    private final PostingRepository postingRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public PostingController(
            PostingRepository postingRepository,
            AccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository) {

        this.postingRepository = postingRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @PostMapping
    public ResponseEntity<?> createPosting(@RequestBody Posting posting) {

        if (posting.getAmount() == null ||
                posting.getAmount().signum() <= 0) {

            return ResponseEntity.badRequest()
                    .body("Amount must be greater than zero");
        }

        if (posting.getDirection() == null) {
            return ResponseEntity.badRequest()
                    .body("Posting direction is required");
        }

        if (posting.getAccount() == null ||
                posting.getAccount().getId() == null) {

            return ResponseEntity.badRequest()
                    .body("Account ID is required");
        }

        if (posting.getJournalEntry() == null ||
                posting.getJournalEntry().getId() == null) {

            return ResponseEntity.badRequest()
                    .body("Journal Entry ID is required");
        }

        Account account = accountRepository
                .findById(posting.getAccount().getId())
                .orElse(null);

        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Account not found");
        }

        JournalEntry journalEntry = journalEntryRepository
                .findById(posting.getJournalEntry().getId())
                .orElse(null);

        if (journalEntry == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Journal Entry not found");
        }

        if (!Boolean.TRUE.equals(account.getActive())) {
            return ResponseEntity.badRequest()
                    .body("Account is inactive");
        }

        posting.setAccount(account);
        posting.setJournalEntry(journalEntry);

        Posting savedPosting = postingRepository.save(posting);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedPosting);
    }

    @GetMapping
    public ResponseEntity<List<Posting>> getAllPostings() {

        return ResponseEntity.ok(
                postingRepository.findAll()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPostingById(
            @PathVariable Long id) {

        Posting posting = postingRepository
                .findById(id)
                .orElse(null);

        if (posting == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Posting not found");
        }

        return ResponseEntity.ok(posting);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updatePosting(
            @PathVariable Long id,
            @RequestBody Posting updatedPosting) {

        Posting existingPosting = postingRepository
                .findById(id)
                .orElse(null);

        if (existingPosting == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Posting not found");
        }

        if (updatedPosting.getAmount() == null ||
                updatedPosting.getAmount().signum() <= 0) {

            return ResponseEntity.badRequest()
                    .body("Amount must be greater than zero");
        }

        if (updatedPosting.getDirection() == null) {
            return ResponseEntity.badRequest()
                    .body("Posting direction is required");
        }

        existingPosting.setAmount(updatedPosting.getAmount());
        existingPosting.setDirection(updatedPosting.getDirection());
        existingPosting.setDescription(updatedPosting.getDescription());

        Posting savedPosting = postingRepository.save(existingPosting);

        return ResponseEntity.ok(savedPosting);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePosting(
            @PathVariable Long id) {

        Posting posting = postingRepository
                .findById(id)
                .orElse(null);

        if (posting == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Posting not found");
        }

        postingRepository.delete(posting);

        return ResponseEntity.ok(
                "Posting deleted successfully"
        );
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<Posting>> getByAccount(
            @PathVariable Long accountId) {

        return ResponseEntity.ok(
                postingRepository.findByAccountId(accountId)
        );
    }

    @GetMapping("/journal/{journalEntryId}")
    public ResponseEntity<List<Posting>> getByJournalEntry(
            @PathVariable Long journalEntryId) {

        return ResponseEntity.ok(
                postingRepository.findByJournalEntryId(journalEntryId)
        );
    }
}