package edu.campusbite.servlet;

import com.google.gson.JsonObject;
import edu.campusbite.dao.SlotDao;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;
import edu.campusbite.util.CsvDataStore.State;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/admin/*")
public class AdminServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        try {
            String path = request.getPathInfo();
            if ("/stats".equals(path)) Json.send(response, 200, CsvDataStore.read(AdminServlet::stats));
            else if ("/reports".equals(path)) Json.send(response, 200, CsvDataStore.read(state ->
                    report(state, "week".equals(request.getParameter("range")) ? 7 : 1)));
            else if ("/users".equals(path)) Json.send(response, 200,
                    Map.of("users", CsvDataStore.read(AdminServlet::users)));
            else if ("/settings".equals(path)) Json.send(response, 200,
                    Map.of("settings", CsvDataStore.read(AdminServlet::settings)));
            else Json.error(response, 404, "Endpoint not found.");
        } catch (IOException e) {
            getServletContext().log("Admin CSV read failed.", e);
            Json.error(response, 500, "Administrative data is temporarily unavailable.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        try {
            String path = request.getPathInfo();
            if (path != null && path.matches("/users/\\d+"))
                updateUser(request, response, Long.parseLong(path.substring("/users/".length())));
            else if ("/settings".equals(path)) updateSettings(request, response);
            else Json.error(response, 404, "Endpoint not found.");
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Admin CSV update failed.", e);
            Json.error(response, 500, "Could not save the administrative change.");
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Json.error(response, 400, "The administrative update request is invalid.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        if (!"/slots/generate".equals(request.getPathInfo())) {
            Json.error(response, 404, "Endpoint not found.");
            return;
        }
        try {
            JsonObject body = Json.body(request);
            if (!body.has("date") || !body.has("startTime") || !body.has("endTime"))
                throw new IllegalArgumentException("Provide a date, start time and end time.");
            LocalDate date = LocalDate.parse(body.get("date").getAsString());
            LocalTime start = LocalTime.parse(body.get("startTime").getAsString());
            LocalTime end = LocalTime.parse(body.get("endTime").getAsString());
            if (date.isBefore(LocalDate.now()) || !end.isAfter(start))
                throw new IllegalArgumentException("Choose a current/future date and an end time after the start.");
            if (date.equals(LocalDate.now()) && !start.isAfter(LocalTime.now()))
                throw new IllegalArgumentException("For today, choose a start time that has not passed.");
            if (!new SlotDao().withinOperatingHours(start, end))
                throw new IllegalArgumentException("Pickup time must be within canteen operating hours.");
            Json.send(response, 201, Map.of("created", generateSlots(date, start, end)));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Pickup slot generation failed.", e);
            Json.error(response, 500, "Could not generate pickup slots.");
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Json.error(response, 400, "Provide a valid slot date and time range.");
        }
    }

    private void updateUser(HttpServletRequest request, HttpServletResponse response, long id) throws IOException {
        JsonObject body = Json.body(request);
        if (!body.has("role") || !body.has("active"))
            throw new IllegalArgumentException("Provide a role and active state.");
        String role = body.get("role").getAsString().toUpperCase();
        if (!List.of("STUDENT", "STAFF", "ADMIN").contains(role))
            throw new IllegalArgumentException("Select a valid account role.");
        boolean updated = CsvDataStore.update(state -> {
            Record user = state.find("user", id);
            if (user == null) return false;
            user.set("role", role);
            user.set("active", Boolean.toString(body.get("active").getAsBoolean()));
            return true;
        });
        if (!updated) {
            Json.error(response, 404, "User not found.");
            return;
        }
        Json.send(response, 200, Map.of("message", "Account updated."));
    }

    private void updateSettings(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonObject body = Json.body(request);
        Map<String, String> numericSettings = Map.of(
                "slot_duration_minutes", "slotDurationMinutes",
                "slot_capacity", "slotCapacity",
                "cancellation_cutoff_minutes", "cancellationCutoffMinutes");
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : numericSettings.entrySet()) {
            if (!body.has(item.getValue())) continue;
            int value = body.get(item.getValue()).getAsInt();
            boolean invalid = switch (item.getKey()) {
                case "slot_duration_minutes" -> value < 1 || value > 60;
                case "slot_capacity" -> value < 1 || value > 1000;
                default -> value < 0 || value > 1440;
            };
            if (invalid)
                throw new IllegalArgumentException("Settings values are outside the allowed range.");
            updates.put(item.getKey(), Integer.toString(value));
        }
        if (body.has("canteenOpen") || body.has("canteenClose")) {
            if (!body.has("canteenOpen") || !body.has("canteenClose"))
                throw new IllegalArgumentException("Provide both opening and closing times.");
            LocalTime opens = LocalTime.parse(body.get("canteenOpen").getAsString());
            LocalTime closes = LocalTime.parse(body.get("canteenClose").getAsString());
            if (!closes.isAfter(opens))
                throw new IllegalArgumentException("Closing time must be after opening time.");
            updates.put("canteen_open", opens.toString());
            updates.put("canteen_close", closes.toString());
        }
        if (updates.isEmpty()) throw new IllegalArgumentException("Provide at least one setting to update.");
        CsvDataStore.update(state -> {
            for (Map.Entry<String, String> item : updates.entrySet()) {
                Record setting = state.all("setting").stream()
                        .filter(row -> item.getKey().equals(row.get("settingKey"))).findFirst().orElse(null);
                if (setting == null) {
                    setting = state.create("setting");
                    setting.set("settingKey", item.getKey());
                    state.add(setting);
                }
                setting.set("settingValue", item.getValue());
            }
            return null;
        });
        Json.send(response, 200, Map.of("message", "Settings saved."));
    }

    private static int generateSlots(LocalDate date, LocalTime start, LocalTime end) throws IOException {
        return CsvDataStore.update(state -> {
            int duration = setting(state, "slot_duration_minutes", 5);
            int capacity = setting(state, "slot_capacity", 30);
            int created = 0;
            for (LocalTime cursor = start; cursor.isBefore(end);) {
                LocalTime next = cursor.plusMinutes(duration);
                if (next.isAfter(end)) next = end;
                LocalTime slotStart = cursor;
                boolean exists = state.all("slot").stream().anyMatch(slot ->
                        slot.dateValue("slotDate").equals(date) && slot.timeValue("startTime").equals(slotStart));
                if (!exists) {
                    Record slot = state.create("slot");
                    slot.set("slotDate", date.toString());
                    slot.set("startTime", cursor.toString());
                    slot.set("endTime", next.toString());
                    slot.set("capacity", Integer.toString(capacity));
                    slot.set("reservedCount", "0");
                    slot.set("available", "true");
                    state.add(slot);
                    created++;
                }
                cursor = next;
            }
            return created;
        });
    }

    private static int setting(State state, String key, int fallback) {
        return state.all("setting").stream().filter(row -> key.equals(row.get("settingKey")))
                .map(row -> row.get("settingValue")).map(Integer::parseInt).findFirst().orElse(fallback);
    }

    private static Map<String, Object> stats(State state) {
        LocalDate today = LocalDate.now();
        List<Record> orders = state.all("order").stream()
                .filter(order -> LocalDateTime.parse(order.get("createdAt")).toLocalDate().equals(today)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ordersToday", orders.size());
        result.put("revenueToday", orders.stream().filter(order -> !"CANCELLED".equals(order.get("status")))
                .map(order -> order.decimalValue("totalAmount")).reduce(BigDecimal.ZERO, BigDecimal::add));
        Map<String, Long> counts = new LinkedHashMap<>();
        orders.forEach(order -> counts.merge(order.get("status"), 1L, Long::sum));
        result.put("statusCounts", counts);
        Map<String, Long> popular = new LinkedHashMap<>();
        state.all("orderItem").stream().filter(item -> orders.stream().anyMatch(order ->
                        order.longValue("id") == item.longValue("orderId")
                                && !"CANCELLED".equals(order.get("status"))))
                .forEach(item -> popular.merge(item.get("itemNameSnapshot"), (long) item.intValue("quantity"), Long::sum));
        result.put("popularItems", popular.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(item -> Map.of("name", item.getKey(), "quantity", item.getValue())).toList());
        Map<Integer, Long> hours = new LinkedHashMap<>();
        orders.forEach(order -> hours.merge(LocalDateTime.parse(order.get("createdAt")).getHour(), 1L, Long::sum));
        result.put("peakHour", hours.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null));
        List<Long> preparationMinutes = orders.stream().filter(order -> !order.get("readyAt").isBlank())
                .map(order -> Duration.between(LocalDateTime.parse(order.get("createdAt")),
                        LocalDateTime.parse(order.get("readyAt"))).toMinutes()).toList();
        result.put("averagePreparationMinutes", preparationMinutes.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(preparationMinutes.stream().mapToLong(Long::longValue).average().orElse(0)));
        return result;
    }

    private static Map<String, Object> report(State state, int days) {
        LocalDate from = LocalDate.now().minusDays(days - 1L);
        Map<LocalDate, List<Record>> grouped = new LinkedHashMap<>();
        state.all("order").stream().filter(order ->
                        !LocalDateTime.parse(order.get("createdAt")).toLocalDate().isBefore(from))
                .forEach(order -> grouped.computeIfAbsent(
                        LocalDateTime.parse(order.get("createdAt")).toLocalDate(), ignored -> new ArrayList<>()).add(order));
        List<Map<String, Object>> daily = grouped.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, List<Record>>comparingByKey().reversed())
                .map(entry -> {
                    List<Record> dayOrders = entry.getValue();
                    long cancelled = dayOrders.stream().filter(order -> "CANCELLED".equals(order.get("status"))).count();
                    BigDecimal revenue = dayOrders.stream().filter(order -> !"CANCELLED".equals(order.get("status")))
                            .map(order -> order.decimalValue("totalAmount")).reduce(BigDecimal.ZERO, BigDecimal::add);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", entry.getKey().toString());
                    row.put("orders", dayOrders.size());
                    row.put("activeOrCompleted", dayOrders.size() - cancelled);
                    row.put("revenue", revenue);
                    row.put("cancelled", cancelled);
                    return row;
                }).toList();
        return Map.of("daily", daily, "rangeDays", days);
    }

    private static List<Map<String, Object>> users(State state) {
        return state.all("user").stream().sorted((first, second) ->
                        second.get("createdAt").compareTo(first.get("createdAt")))
                .map(user -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", user.longValue("id"));
                    row.put("name", user.get("name"));
                    row.put("email", user.get("email"));
                    row.put("role", user.get("role"));
                    row.put("active", user.booleanValue("active"));
                    row.put("createdAt", user.get("createdAt"));
                    return row;
                }).toList();
    }

    private static Map<String, String> settings(State state) {
        Map<String, String> settings = new LinkedHashMap<>();
        state.all("setting").forEach(setting ->
                settings.put(setting.get("settingKey"), setting.get("settingValue")));
        return settings;
    }
}
