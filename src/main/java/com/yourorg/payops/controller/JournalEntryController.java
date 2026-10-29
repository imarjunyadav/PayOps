package com.yourorg.payops.controller;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.entity.SettlementRecord;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.exception.ConflictException;
import com.yourorg.payops.exception.ResourceNotFoundException;
import com.yourorg.payops.model.PostingDirection;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.PaymentRepository;
import com.yourorg.payops.repository.PostingRepository;
import com.yourorg.payops.repository.SettlementRecordRepository;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/journal-entries")
public class JournalEntryController {
    private final JournalEntryRepository journals;
    private final PostingRepository postings;
    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final SettlementRecordRepository settlementRecords;

    public JournalEntryController(JournalEntryRepository journals, PostingRepository postings,
            AccountRepository accounts, PaymentRepository payments,
            SettlementRecordRepository settlementRecords) {
        this.journals = journals;
        this.postings = postings;
        this.accounts = accounts;
        this.payments = payments;
        this.settlementRecords = settlementRecords;
    }

    @GetMapping("/{id}")
    public JournalEntry get(@PathVariable Long id) {
        return findJournal(id);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<JournalEntry> create(@Valid @RequestBody JournalEntry body) {
        if (journals.existsByEntryNumber(body.getEntryNumber())) {
            throw new ConflictException("Journal entry number already exists");
        }
        body.setId(null);
        body.setCreatedAt(null);
        body.setCreatedBy(null);
        body.setPayment(resolvePayment(body.getPayment()));
        body.setSettlementRecord(resolveSettlementRecord(body.getSettlementRecord()));
        JournalEntry saved = journals.save(body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping("/{journalEntryId}/postings")
    public List<Posting> listPostings(@PathVariable Long journalEntryId) {
        findJournal(journalEntryId);
        return postings.findByJournalEntryId(journalEntryId);
    }

    /** Adds a complete, balanced set of lines atomically; individual line writes are not exposed. */
    @PostMapping("/{journalEntryId}/postings")
    @Transactional
    public ResponseEntity<List<Posting>> addPostings(@PathVariable Long journalEntryId,
            @RequestBody List<@Valid Posting> body) {
        JournalEntry journal = findJournal(journalEntryId);
        if (body == null || body.size() < 2) {
            throw new BusinessRuleException("At least two postings are required for a balanced journal");
        }

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<Posting> validated = new ArrayList<>(body.size());
        for (Posting posting : body) {
            if (posting == null || posting.getAmount() == null || posting.getAmount().signum() <= 0) {
                throw new BusinessRuleException("Posting amount must be greater than zero");
            }
            if (posting.getDirection() == null) {
                throw new BusinessRuleException("Posting direction is required");
            }
            Account suppliedAccount = posting.getAccount();
            if (suppliedAccount == null || suppliedAccount.getId() == null) {
                throw new ResourceNotFoundException("Account", null);
            }
            Account account = accounts.findById(suppliedAccount.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", suppliedAccount.getId()));
            posting.setId(null);
            posting.setCreatedAt(null);
            posting.setCreatedBy(null);
            posting.setJournalEntry(journal);
            posting.setAccount(account);
            if (posting.getDirection() == PostingDirection.DEBIT) {
                debits = debits.add(posting.getAmount());
            } else {
                credits = credits.add(posting.getAmount());
            }
            validated.add(posting);
        }
        if (debits.compareTo(credits) != 0) {
            throw new BusinessRuleException("Journal postings must balance: total debits must equal total credits");
        }

        List<Posting> saved = postings.saveAll(validated);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
        return ResponseEntity.created(location).body(saved);
    }

    private JournalEntry findJournal(Long id) {
        return journals.findById(id).orElseThrow(() -> new ResourceNotFoundException("JournalEntry", id));
    }

    private Payment resolvePayment(Payment supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("Payment", null);
        return payments.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", supplied.getId()));
    }

    private SettlementRecord resolveSettlementRecord(SettlementRecord supplied) {
        if (supplied == null) return null;
        if (supplied.getId() == null) throw new ResourceNotFoundException("SettlementRecord", null);
        return settlementRecords.findById(supplied.getId())
                .orElseThrow(() -> new ResourceNotFoundException("SettlementRecord", supplied.getId()));
    }
}
