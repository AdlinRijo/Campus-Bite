package edu.campusbite.model;

public record Notification(long id, long orderId, String message, boolean read, String createdAt) {}
