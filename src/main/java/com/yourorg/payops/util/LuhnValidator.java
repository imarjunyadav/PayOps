package com.yourorg.payops.util;

public final class LuhnValidator {

    private LuhnValidator() {}

    /**
     * Validates card number using the Luhn algorithm.
     * Expects a string containing 13 to 19 numeric digits (spaces/hyphens allowed and stripped).
     */
    public static boolean isValid(String cardNumber) {
        if (cardNumber == null) {
            return false;
        }
        String sanitized = cardNumber.replaceAll("[\\s-]", "");
        if (!sanitized.matches("^\\d{13,19}$")) {
            return false;
        }

        int sum = 0;
        boolean alternate = false;
        for (int i = sanitized.length() - 1; i >= 0; i--) {
            int n = sanitized.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}
