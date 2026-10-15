package edu.campusbite.model;

public record PickupSlot(long id, String date, String startTime, String endTime,
                         int capacity, int reservedCount, int remaining) {}
