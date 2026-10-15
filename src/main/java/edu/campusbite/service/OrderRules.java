package edu.campusbite.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class OrderRules {
    private OrderRules() {}

    public static boolean allowsTransition(String current, String next) {
        return switch (current) {
            case "PENDING" -> "CONFIRMED".equals(next) || "CANCELLED".equals(next);
            case "CONFIRMED" -> "PREPARING".equals(next) || "CANCELLED".equals(next);
            case "PREPARING" -> "READY".equals(next);
            case "READY" -> "COLLECTED".equals(next);
            default -> false;
        };
    }

    public static String token(LocalDate slotDate, long orderId) {
        if (slotDate == null || orderId < 1) throw new IllegalArgumentException("A valid date and order id are required.");
        return "CB-" + slotDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%05d", orderId);
    }
}
