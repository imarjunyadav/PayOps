# Changes (IMPORTANT: your payops-phase0.zip did not arrive in the chat — /mnt/user-data/uploads was empty)
I could not read your Posting, SettlementFile, SettlementRecord, Account, JournalEntry. I regenerated them from your stated conventions. **Do a diff against yours and keep your own field names where they differ.** This zip is an OVERLAY: copy `src/`, `sql/`, `docs/` into your project (replace Account and JournalEntry).
Assumed baseline vs what is now in code:
- **Posting**: needs `journalEntry` (ManyToOne JournalEntry, journal_entry_id), `account` (ManyToOne Account, account_id), `direction` (DEBIT/CREDIT), `amount > 0`. Added audit fields (CreatedAudit).
- **SettlementFile**: fileName (unique), `switchDatabase` link, settlementDate, status, recordCount, totalAmount, receivedAt, audit fields.
- **SettlementRecord**: kept `paymentReference`; ADDED optional `payment` (payment_id, SET NULL); amount, fee (fee ≤ amount), currency, transactionDate; link to `settlementFile`.
- **Account / JournalEntry**: placeholders replaced by full entities. Nothing renamed on purpose; if your placeholders had other field names, tell me.
- Lombok NOT used (explicit getters/setters). Needs spring-boot-starter-validation and Spring Boot 3 (jakarta.*).
