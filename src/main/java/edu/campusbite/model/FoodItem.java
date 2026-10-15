package edu.campusbite.model;

import java.math.BigDecimal;

public record FoodItem(long id, String name, String description, String category,
                       BigDecimal price, int stockQuantity, String imageUrl, boolean available) {}
