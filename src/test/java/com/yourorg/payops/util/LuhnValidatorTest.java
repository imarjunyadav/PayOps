package com.yourorg.payops.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuhnValidatorTest {

    @Test
    void acceptsValidLuhnCards() {
        assertTrue(LuhnValidator.isValid("4111111111111111"));
        assertTrue(LuhnValidator.isValid("4000000000000002"));
        assertTrue(LuhnValidator.isValid("4000000000000259")); // Ends in 9
        assertTrue(LuhnValidator.isValid("4111-1111-1111-1111")); // Hyphenated
        assertTrue(LuhnValidator.isValid("4111 1111 1111 1111")); // Spaced
    }

    @Test
    void rejectsInvalidLuhnCards() {
        assertFalse(LuhnValidator.isValid("4111111111111112")); // Wrong check digit
        assertFalse(LuhnValidator.isValid("1234567812345678"));
        assertFalse(LuhnValidator.isValid(""));
        assertFalse(LuhnValidator.isValid(null));
        assertFalse(LuhnValidator.isValid("abcdef1234567890"));
        assertFalse(LuhnValidator.isValid("123")); // Too short
        assertFalse(LuhnValidator.isValid("12345678901234567890123")); // Too long
    }
}
