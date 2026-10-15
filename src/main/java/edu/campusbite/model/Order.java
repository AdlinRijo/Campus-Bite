package edu.campusbite.model;

import java.math.BigDecimal;
import java.util.List;

public record Order(long id, long userId, String studentName, String tokenNumber, long slotId,
                    BigDecimal totalAmount, String status, String paymentMethod, String paymentStatus,
                    String createdAt, String updatedAt, String slotDate, String slotStart, String slotEnd,
                    int ordersAhead, List<OrderItem> items) {}
