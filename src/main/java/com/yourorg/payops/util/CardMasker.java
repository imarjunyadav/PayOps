package com.yourorg.payops.util;

public final class CardMasker {

    private CardMasker() {}

    /**
     * Masks a card number preserving the first 6 (BIN) and last 4 digits.
     * E.g. 4111111111111111 -> 411111******1111
     */
    public static String mask(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }
        String sanitized = cardNumber.replaceAll("[\\s-]", "");
        int len = sanitized.length();
        if (len < 10) {
            return sanitized.length() > 4 ? "****" + sanitized.substring(len - 4) : "****";
        }
        String prefix = sanitized.substring(0, 6);
        String suffix = sanitized.substring(len - 4);
        String maskedMiddle = "*".repeat(len - 10);
        return prefix + maskedMiddle + suffix;
    }
}
