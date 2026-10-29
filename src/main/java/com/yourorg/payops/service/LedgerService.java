package com.yourorg.payops.service;

import com.yourorg.payops.entity.Account;
import com.yourorg.payops.entity.JournalEntry;
import com.yourorg.payops.entity.Payment;
import com.yourorg.payops.entity.Posting;
import com.yourorg.payops.exception.BusinessRuleException;
import com.yourorg.payops.model.JournalSource;
import com.yourorg.payops.model.PostingDirection;
import com.yourorg.payops.repository.AccountRepository;
import com.yourorg.payops.repository.JournalEntryRepository;
import com.yourorg.payops.repository.PostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JournalEntryRepository journalEntries;
    private final PostingRepository postings;
    private final AccountRepository accounts;

    public LedgerService(JournalEntryRepository journalEntries, PostingRepository postings, AccountRepository accounts) {
        this.journalEntries = journalEntries;
        this.postings = postings;
        this.accounts = accounts;
    }

    /**
     * Atomically creates the two balanced double-entry ledger postings for an APPROVED payment.
     * Idempotent: If a ledger entry already exists for this payment approval, it returns the existing entry.
     * Rule:
     *   Debit:  Account 1100 (Acquirer Receivable)
     *   Credit: Account 2100 (Merchant Payable)
     */
    @Transactional
    public JournalEntry recordPaymentApproval(Payment payment) {
        if (payment == null || payment.getId() == null) {
            throw new IllegalArgumentException("Payment must be persisted before creating ledger postings");
        }

        // Idempotency check: prevent duplicate journal entries if retried
        Optional<JournalEntry> existing = journalEntries.findByPaymentIdAndSource(payment.getId(), JournalSource.PAYMENT_APPROVED);
        if (existing.isPresent()) {
            log.info("Ledger entry already exists for payment id={}: {}", payment.getId(), existing.get().getEntryNumber());
            return existing.get();
        }

        Account acquirerReceivable = accounts.findByCode("1100")
                .orElseThrow(() -> new BusinessRuleException("Required ledger account 1100 (Acquirer Receivable) not found"));
        Account merchantPayable = accounts.findByCode("2100")
                .orElseThrow(() -> new BusinessRuleException("Required ledger account 2100 (Merchant Payable) not found"));

        String entryNumber = "JE-APP-" + payment.getReference();
        // Fallback uniqueness if entryNumber already taken by an unrelated reference
        if (journalEntries.existsByEntryNumber(entryNumber)) {
            entryNumber = "JE-APP-" + payment.getId() + "-" + System.currentTimeMillis();
        }

        JournalEntry entry = new JournalEntry();
        entry.setEntryNumber(entryNumber);
        entry.setSource(JournalSource.PAYMENT_APPROVED);
        entry.setDescription("Approved payment: " + payment.getReference());
        entry.setPayment(payment);
        entry.setEntryDate(LocalDateTime.now());
        JournalEntry savedEntry = journalEntries.save(entry);

        // Debit: Acquirer Receivable (1100)
        Posting debit = new Posting();
        debit.setJournalEntry(savedEntry);
        debit.setAccount(acquirerReceivable);
        debit.setDirection(PostingDirection.DEBIT);
        debit.setAmount(payment.getAmount());
        debit.setDescription("Acquirer receivable for " + payment.getReference());
        postings.save(debit);

        // Credit: Merchant Payable (2100)
        Posting credit = new Posting();
        credit.setJournalEntry(savedEntry);
        credit.setAccount(merchantPayable);
        credit.setDirection(PostingDirection.CREDIT);
        credit.setAmount(payment.getAmount());
        credit.setDescription("Merchant payable for " + payment.getReference());
        postings.save(credit);

        log.info("Created 2 balanced ledger postings for payment {}: Dr 1100, Cr 2100, amount={}",
                payment.getReference(), payment.getAmount());
        return savedEntry;
    }
}
