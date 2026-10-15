package edu.campusbite.service;

import edu.campusbite.dao.OrderDao;
import edu.campusbite.model.Order;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;
import edu.campusbite.util.CsvDataStore.State;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OrderService {
    public Order place(long userId, long slotId, Map<Long, Integer> requestedItems,
                       String paymentMethod, String pickupName, String idempotencyKey) throws IOException {
        if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 30)
            throw new IllegalArgumentException("Your cart is empty or contains too many items.");
        if (!"GOOGLE_PAY_SIMULATION".equals(paymentMethod))
            throw new IllegalArgumentException("Only the Google Pay simulation is available.");
        String customerName = pickupName == null ? "" : pickupName.trim();
        if (customerName.length() > 100 || (userId == 0 && customerName.length() < 2))
            throw new IllegalArgumentException("Enter a pickup name between 2 and 100 characters.");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80)
            throw new IllegalArgumentException("A request id is required. Please try again.");
        TreeMap<Long, Integer> items = new TreeMap<>(requestedItems);
        for (int quantity : items.values()) {
            if (quantity < 1 || quantity > 50)
                throw new IllegalArgumentException("Each item quantity must be between 1 and 50.");
        }

        return CsvDataStore.update(state -> {
            Order previous = OrderDao.findByIdempotency(state, userId, idempotencyKey);
            if (previous != null) {
                return previous;
            }
            Record slot = state.find("slot", slotId);
            if (slot == null || !slot.booleanValue("available")
                    || !LocalDateTime.of(slot.dateValue("slotDate"), slot.timeValue("startTime"))
                    .isAfter(LocalDateTime.now())
                    || slot.intValue("reservedCount") >= slot.intValue("capacity")) {
                throw new IllegalArgumentException("That pickup slot has just filled or passed. Choose another slot.");
            }

            BigDecimal total = BigDecimal.ZERO;
            Map<Long, Item> verified = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : items.entrySet()) {
                Record food = state.find("food", entry.getKey());
                int quantity = entry.getValue();
                if (food == null || !food.booleanValue("available")) {
                    throw new IllegalArgumentException("A selected item is no longer available.");
                }
                if (food.intValue("stockQuantity") < quantity) {
                    throw new IllegalArgumentException("Not enough stock for " + food.get("name") + ".");
                }
                BigDecimal price = food.decimalValue("price");
                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity));
                total = total.add(subtotal);
                verified.put(entry.getKey(), new Item(food, price, quantity, subtotal));
            }

            slot.set("reservedCount", Integer.toString(slot.intValue("reservedCount") + 1));
            Record order = state.create("order");
            order.set("userId", Long.toString(userId));
            if (!customerName.isEmpty()) order.set("name", customerName);
            order.set("slotId", Long.toString(slotId));
            order.set("totalAmount", total.toPlainString());
            order.set("status", "PENDING");
            order.set("paymentMethod", paymentMethod);
            order.set("paymentStatus", "SIMULATED");
            order.set("idempotencyKey", idempotencyKey);
            order.set("tokenNumber", OrderRules.token(slot.dateValue("slotDate"), order.longValue("id")));
            order.set("updatedAt", order.get("createdAt"));
            state.add(order);
            for (Map.Entry<Long, Item> entry : verified.entrySet()) {
                Item item = entry.getValue();
                item.food().set("stockQuantity",
                        Integer.toString(item.food().intValue("stockQuantity") - item.quantity()));
                Record orderItem = state.create("orderItem");
                orderItem.set("orderId", Long.toString(order.longValue("id")));
                orderItem.set("foodItemId", Long.toString(entry.getKey()));
                orderItem.set("itemNameSnapshot", item.food().get("name"));
                orderItem.set("unitPrice", item.price().toPlainString());
                orderItem.set("quantity", Integer.toString(item.quantity()));
                orderItem.set("subtotal", item.subtotal().toPlainString());
                state.add(orderItem);
            }
            return OrderDao.get(state, order.longValue("id"));
        });
    }

    public Order get(long id, long userId, boolean privileged) throws IOException {
        Order order = new OrderDao().get(id);
        return order == null || (!privileged && order.userId() != userId) ? null : order;
    }

    public List<Order> list(long userId, boolean privileged, String status) throws IOException {
        return new OrderDao().list(userId, privileged, status);
    }

    public void changeStatus(long orderId, String nextStatus, String reason) throws IOException {
        String next = nextStatus == null ? "" : nextStatus.toUpperCase();
        if ("CANCELLED".equals(next)) {
            cancel(orderId, null, true, reason);
            return;
        }
        CsvDataStore.update(state -> {
            Record order = state.find("order", orderId);
            if (order == null) {
                throw new IllegalArgumentException("Order not found.");
            }
            String current = order.get("status");
            if (!OrderRules.allowsTransition(current, next)) {
                throw new IllegalArgumentException("Invalid order status transition.");
            }
            order.set("status", next);
            order.set("updatedAt", LocalDateTime.now().toString());
            if ("READY".equals(next)) {
                order.set("readyAt", LocalDateTime.now().toString());
                addNotification(state, order.longValue("userId"), order.longValue("id"),
                        "Your order " + order.get("tokenNumber") + " is ready for pickup.");
            }
            return null;
        });
    }

    public void cancel(long orderId, Long requestingUserId, boolean staff, String reason) throws IOException {
        CsvDataStore.update(state -> {
            Record order = state.find("order", orderId);
            if (order == null) {
                throw new IllegalArgumentException("Order not found.");
            }
            long ownerId = order.longValue("userId");
            if (!staff && (requestingUserId == null || ownerId != requestingUserId)) {
                throw new IllegalArgumentException("Order not found.");
            }
            String status = order.get("status");
            if (!"PENDING".equals(status) && !"CONFIRMED".equals(status)) {
                throw new IllegalArgumentException("This order can no longer be cancelled.");
            }
            Record slot = state.find("slot", order.longValue("slotId"));
            if (!staff) {
                int cutoff = setting(state, "cancellation_cutoff_minutes", 10);
                LocalDateTime pickup = LocalDateTime.of(slot.dateValue("slotDate"), slot.timeValue("startTime"));
                if (!pickup.isAfter(LocalDateTime.now().plusMinutes(cutoff))) {
                    throw new IllegalArgumentException("The cancellation deadline has passed.");
                }
            }
            for (Record item : state.all("orderItem")) {
                if (item.longValue("orderId") != orderId) continue;
                Record food = state.find("food", item.longValue("foodItemId"));
                if (food != null) {
                    food.set("stockQuantity", Integer.toString(
                            food.intValue("stockQuantity") + item.intValue("quantity")));
                }
            }
            if (slot != null) {
                slot.set("reservedCount", Integer.toString(Math.max(0, slot.intValue("reservedCount") - 1)));
            }
            String safeReason = reason == null || reason.isBlank() ? "Cancelled by request."
                    : reason.substring(0, Math.min(reason.length(), 300));
            order.set("status", "CANCELLED");
            order.set("cancellationReason", safeReason);
            order.set("updatedAt", LocalDateTime.now().toString());
            addNotification(state, ownerId, orderId, "Your order was cancelled.");
            return null;
        });
    }

    private static int setting(State state, String key, int fallback) {
        return state.all("setting").stream().filter(row -> key.equals(row.get("settingKey")))
                .map(row -> row.get("settingValue")).map(Integer::parseInt)
                .findFirst().orElse(fallback);
    }

    private static void addNotification(State state, long userId, long orderId, String message) {
        Record notification = state.create("notification");
        notification.set("userId", Long.toString(userId));
        notification.set("orderId", Long.toString(orderId));
        notification.set("message", message);
        notification.set("isRead", "false");
        state.add(notification);
    }

    private record Item(Record food, BigDecimal price, int quantity, BigDecimal subtotal) {}
}
