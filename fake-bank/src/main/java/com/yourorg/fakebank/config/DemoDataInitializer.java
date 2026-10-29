package com.yourorg.fakebank.config;

import com.yourorg.fakebank.entity.BankCard;
import com.yourorg.fakebank.repository.BankCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DemoDataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final BankCardRepository cards;

    public DemoDataInitializer(BankCardRepository cards) {
        this.cards = cards;
    }

    @Override
    public void run(String... args) {
        seedCardIfMissing("4111111111111111", "John Demo Merchant", 12, 2028, "123", new BigDecimal("50000.0000"));
        seedCardIfMissing("4000000000000002", "Low Balance Demo", 12, 2028, "123", new BigDecimal("5.0000"));
        seedCardIfMissing("4000000000000259", "Delayed Timeout Demo", 12, 2028, "123", new BigDecimal("10000.0000"));
    }

    private void seedCardIfMissing(String cardNumber, String name, int month, int year, String cvv, BigDecimal balance) {
        if (cards.findByCardNumber(cardNumber).isEmpty()) {
            BankCard card = new BankCard(cardNumber, name, month, year, cvv, balance, "INR", "ACTIVE");
            cards.save(card);
            log.info("Seeded demo bank card: {} (balance={})", cardNumber, balance);
        }
    }
}
