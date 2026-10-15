package edu.campusbite.dao;

import edu.campusbite.model.PickupSlot;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

public class SlotDao {
    public List<PickupSlot> list(LocalDate date) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        return CsvDataStore.read(state -> state.all("slot").stream()
                .filter(slot -> slot.booleanValue("available") && slot.dateValue("slotDate").equals(date)
                        && LocalDateTime.of(slot.dateValue("slotDate"), slot.timeValue("startTime")).isAfter(now))
                .sorted(Comparator.comparing(slot -> slot.timeValue("startTime")))
                .map(SlotDao::toModel).toList());
    }

    public long create(LocalDate date, String startTime, String endTime, int capacity) throws IOException {
        LocalTime start = LocalTime.parse(startTime);
        LocalTime end = LocalTime.parse(endTime);
        if (capacity < 1 || !end.isAfter(start)) {
            throw new IllegalArgumentException("Check the slot date, times, and capacity.");
        }
        return CsvDataStore.update(state -> {
            boolean duplicate = state.all("slot").stream().anyMatch(slot ->
                    slot.dateValue("slotDate").equals(date) && slot.timeValue("startTime").equals(start));
            if (duplicate) {
                throw new CsvDataStore.ConflictException("A slot already starts at that time.");
            }
            Record slot = state.create("slot");
            slot.set("slotDate", date.toString());
            slot.set("startTime", start.toString());
            slot.set("endTime", end.toString());
            slot.set("capacity", Integer.toString(capacity));
            slot.set("reservedCount", "0");
            slot.set("available", "true");
            state.add(slot);
            return slot.longValue("id");
        });
    }

    public boolean withinOperatingHours(LocalTime start, LocalTime end) throws IOException {
        return CsvDataStore.read(state -> {
            LocalTime opens = LocalTime.parse(setting(state, "canteen_open", "07:30"));
            LocalTime closes = LocalTime.parse(setting(state, "canteen_close", "15:00"));
            return !start.isBefore(opens) && !end.isAfter(closes);
        });
    }

    private static String setting(CsvDataStore.State state, String key, String fallback) {
        return state.all("setting").stream().filter(row -> key.equals(row.get("settingKey")))
                .map(row -> row.get("settingValue")).findFirst().orElse(fallback);
    }

    private static PickupSlot toModel(Record slot) {
        int capacity = slot.intValue("capacity");
        int reserved = slot.intValue("reservedCount");
        return new PickupSlot(slot.longValue("id"), slot.get("slotDate"),
                slot.timeValue("startTime").toString(), slot.timeValue("endTime").toString(),
                capacity, reserved, capacity - reserved);
    }
}
