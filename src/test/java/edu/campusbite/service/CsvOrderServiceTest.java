package edu.campusbite.service;

import edu.campusbite.model.Order;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CsvOrderServiceTest {
    private static Path directory;

    @BeforeAll
    static void useTemporaryCsvFile() throws IOException {
        directory = Files.createTempDirectory("campusbite-csv-test");
        System.setProperty("campusbite.dataFile", directory.resolve("campusbite.csv").toString());
    }

    @AfterAll
    static void removeTemporaryCsvFile() throws IOException {
        Files.deleteIfExists(CsvDataStore.file());
        Files.deleteIfExists(CsvDataStore.file().resolveSibling("campusbite.csv.tmp"));
        Files.deleteIfExists(directory);
        System.clearProperty("campusbite.dataFile");
    }

    @Test
    void csvEscapingPreservesCommasQuotesAndNewlines() throws IOException {
        String description = "item, with a \"quote\"\nand a second line";
        CsvDataStore.update(state -> {
            state.find("food", 1).set("description", description);
            return null;
        });

        assertEquals(description, CsvDataStore.read(state -> state.find("food", 1).get("description")));
    }

    @Test
    void placingAndCancellingOrderUpdatesStockCapacityAndIdempotencyTogether() throws IOException {
        long userId = CsvDataStore.update(state -> {
            Record user = state.create("user");
            user.set("name", "Test Student");
            user.set("email", "student@example.edu");
            user.set("role", "STUDENT");
            user.set("active", "true");
            state.add(user);

            Record slot = state.create("slot");
            slot.set("slotDate", LocalDate.now().plusDays(2).toString());
            slot.set("startTime", "12:30");
            slot.set("endTime", "12:35");
            slot.set("capacity", "5");
            slot.set("reservedCount", "0");
            slot.set("available", "true");
            state.add(slot);
            return user.longValue("id");
        });
        long slotId = CsvDataStore.read(state -> state.all("slot").stream()
                .filter(slot -> slot.dateValue("slotDate").equals(LocalDate.now().plusDays(2)))
                .mapToLong(slot -> slot.longValue("id")).findFirst().orElseThrow());

        OrderService service = new OrderService();
        Order placed = service.place(userId, slotId, Map.of(1L, 2),
                "GOOGLE_PAY_SIMULATION", "Test Student", "request-123");
        Order retry = service.place(userId, slotId, Map.of(1L, 2),
                "GOOGLE_PAY_SIMULATION", "Test Student", "request-123");
        assertEquals(placed.id(), retry.id());
        assertEquals(new BigDecimal("70"), placed.totalAmount());
        assertEquals("SIMULATED", placed.paymentStatus());
        assertNotNull(placed.tokenNumber());

        service.cancel(placed.id(), userId, false, "Testing rollback behavior.");
        CsvDataStore.read(state -> {
            assertEquals(60, state.find("food", 1).intValue("stockQuantity"));
            assertEquals(0, state.find("slot", slotId).intValue("reservedCount"));
            assertEquals("CANCELLED", state.find("order", placed.id()).get("status"));
            assertEquals(1, state.all("notification").size());
            return null;
        });
    }

    @Test
    void guestCanPlaceOrderWithSimulatedPaymentAndPickupName() throws IOException {
        long slotId = CsvDataStore.update(state -> {
            Record slot = state.create("slot");
            slot.set("slotDate", LocalDate.now().plusDays(3).toString());
            slot.set("startTime", "12:30");
            slot.set("endTime", "12:35");
            slot.set("capacity", "5");
            slot.set("reservedCount", "0");
            slot.set("available", "true");
            state.add(slot);
            return slot.longValue("id");
        });

        Order placed = new OrderService().place(0, slotId, Map.of(2L, 1),
                "GOOGLE_PAY_SIMULATION", "Guest Customer", "guest-request");

        assertEquals(0, placed.userId());
        assertEquals("Guest Customer", placed.studentName());
        assertEquals("GOOGLE_PAY_SIMULATION", placed.paymentMethod());
        assertEquals("SIMULATED", placed.paymentStatus());
        assertNotNull(placed.tokenNumber());
    }
}
