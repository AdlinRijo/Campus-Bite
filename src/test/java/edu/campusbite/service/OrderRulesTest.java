package edu.campusbite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OrderRulesTest {
    @Test
    void allowsOnlyForwardLifecycleTransitionsAndPrePreparationCancellation() {
        assertTrue(OrderRules.allowsTransition("PENDING", "CONFIRMED"));
        assertTrue(OrderRules.allowsTransition("CONFIRMED", "PREPARING"));
        assertTrue(OrderRules.allowsTransition("PREPARING", "READY"));
        assertTrue(OrderRules.allowsTransition("READY", "COLLECTED"));
        assertTrue(OrderRules.allowsTransition("PENDING", "CANCELLED"));
        assertTrue(OrderRules.allowsTransition("CONFIRMED", "CANCELLED"));
        assertFalse(OrderRules.allowsTransition("PREPARING", "CANCELLED"));
        assertFalse(OrderRules.allowsTransition("COLLECTED", "READY"));
        assertFalse(OrderRules.allowsTransition("CANCELLED", "CONFIRMED"));
    }

    @Test
    void tokenContainsPickupDateAndNeverCollidesForDifferentOrderIds() {
        LocalDate pickupDate = LocalDate.of(2026, 10, 14);
        assertEquals("CB-20261014-00042", OrderRules.token(pickupDate, 42));
        assertFalse(OrderRules.token(pickupDate, 42).equals(OrderRules.token(pickupDate, 43)));
        assertFalse(OrderRules.token(pickupDate, 42).equals(OrderRules.token(pickupDate.plusDays(1), 42)));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.token(pickupDate, 0));
    }
}
