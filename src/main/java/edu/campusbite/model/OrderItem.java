package edu.campusbite.model;

import java.math.BigDecimal;

public record OrderItem(String name, BigDecimal unitPrice, int quantity, BigDecimal subtotal) {}
