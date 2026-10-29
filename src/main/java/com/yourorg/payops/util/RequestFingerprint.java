package com.yourorg.payops.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestFingerprint {

    private RequestFingerprint() {}

    /**
     * Computes a deterministic SHA-256 hex string for the payment request parameters.
     */
    public static String compute(Long merchantId, BigDecimal amount, String currency, String method, String cardNumber, String reference) {
        String normalizedAmount = (amount != null) ? amount.stripTrailingZeros().toPlainString() : "0";
        String normalizedCurrency = (currency != null) ? currency.trim().toUpperCase() : "";
        String normalizedMethod = (method != null) ? method.trim().toUpperCase() : "";
        String normalizedCard = (cardNumber != null) ? cardNumber.replaceAll("[\\s-]", "") : "";
        String normalizedRef = (reference != null) ? reference.trim() : "";
        String merchantStr = (merchantId != null) ? merchantId.toString() : "";

        String payload = String.join("|", merchantStr, normalizedAmount, normalizedCurrency, normalizedMethod, normalizedCard, normalizedRef);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
