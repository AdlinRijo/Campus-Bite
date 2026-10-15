package edu.campusbite.dao;

import edu.campusbite.model.Order;
import edu.campusbite.model.OrderItem;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;
import edu.campusbite.util.CsvDataStore.State;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

public class OrderDao {
    private static final List<String> STATUSES =
            List.of("PENDING", "CONFIRMED", "PREPARING", "READY", "COLLECTED", "CANCELLED");

    public List<Order> list(long userId, boolean allOrders, String status) throws IOException {
        return CsvDataStore.read(state -> list(state, userId, allOrders, status));
    }

    public Order get(long orderId) throws IOException {
        return CsvDataStore.read(state -> get(state, orderId));
    }

    public Order findByIdempotency(long userId, String key) throws IOException {
        return CsvDataStore.read(state -> findByIdempotency(state, userId, key));
    }

    public static Order get(State state, long orderId) {
        Record order = state.find("order", orderId);
        return order == null ? null : toModel(state, order);
    }

    public static Order findByIdempotency(State state, long userId, String key) {
        return state.all("order").stream()
                .filter(order -> order.longValue("userId") == userId && key.equals(order.get("idempotencyKey")))
                .findFirst().map(order -> toModel(state, order)).orElse(null);
    }

    public static List<Order> list(State state, long userId, boolean allOrders, String status) {
        String selectedStatus = status == null ? "" : status.trim().toUpperCase();
        return state.all("order").stream()
                .filter(order -> allOrders || order.longValue("userId") == userId)
                .filter(order -> selectedStatus.isEmpty() || selectedStatus.equals(order.get("status")))
                .sorted(Comparator.comparingInt((Record order) -> {
                    int index = STATUSES.indexOf(order.get("status"));
                    return index < 0 ? STATUSES.size() : index;
                }).thenComparing(order -> slot(state, order.longValue("slotId")).get("slotDate"))
                        .thenComparing(order -> slot(state, order.longValue("slotId")).get("startTime"))
                .thenComparing(order -> order.get("createdAt")))
                .map(order -> toModel(state, order)).toList();
    }

    private static Order toModel(State state, Record record) {
        Record user = state.find("user", record.longValue("userId"));
        Record slot = slot(state, record.longValue("slotId"));
        List<OrderItem> items = state.all("orderItem").stream()
                .filter(item -> item.longValue("orderId") == record.longValue("id"))
                .map(item -> new OrderItem(item.get("itemNameSnapshot"), item.decimalValue("unitPrice"),
                        item.intValue("quantity"), item.decimalValue("subtotal"))).toList();
        int ordersAhead = (int) state.all("order").stream()
                .filter(other -> other.longValue("slotId") == record.longValue("slotId")
                        && other.longValue("id") < record.longValue("id")
                        && List.of("PENDING", "CONFIRMED", "PREPARING").contains(other.get("status"))).count();
        return new Order(record.longValue("id"), record.longValue("userId"),
                user == null ? record.get("name") : user.get("name"), record.get("tokenNumber"), record.longValue("slotId"),
                record.decimalValue("totalAmount"), record.get("status"), record.get("paymentMethod"),
                record.get("paymentStatus"), record.get("createdAt"), record.get("updatedAt"),
                slot == null ? "" : slot.get("slotDate"), slot == null ? "" : slot.get("startTime"),
                slot == null ? "" : slot.get("endTime"), ordersAhead, items);
    }

    private static Record slot(State state, long id) {
        return state.find("slot", id);
    }
}
