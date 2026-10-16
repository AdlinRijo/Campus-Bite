package edu.campusbite.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.campusbite.model.User;
import edu.campusbite.model.Order;
import edu.campusbite.service.OrderService;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet("/api/orders/*")
public class OrderServlet extends HttpServlet {
    private final OrderService orders = new OrderService();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        User user = ApiAccess.user(request);
        String path = request.getPathInfo();
        try {
            if (path == null || "/".equals(path)) {
                Json.send(response, 200, Map.of("orders", orders.list(user.id(),
                        !"STUDENT".equals(user.role()), request.getParameter("status"))));
                return;
            }
            long id = parseId(path);
            Order order = orders.get(id, user.id(), !"STUDENT".equals(user.role()));
            if (order == null) Json.error(response, 404, "Order not found.");
            else Json.send(response, 200, Map.of("order", order));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, "A valid order id is required.");
        } catch (IOException e) {
            getServletContext().log("Order CSV lookup failed.", e);
            Json.error(response, 500, "Orders are temporarily unavailable.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        User user = ApiAccess.user(request);
        if (user != null && !ApiAccess.requireRole(request, response, "STUDENT")) return;
        try {
            JsonObject body = Json.body(request);
            String payment = value(body, "paymentMethod", null);
            String pickupName = value(body, "pickupName", null);
            String requestId = request.getHeader("Idempotency-Key");
            if (requestId == null || requestId.isBlank()) requestId = value(body, "requestId", null);
            JsonArray lines = body.getAsJsonArray("items");
            Map<Long, Integer> requested = new LinkedHashMap<>();
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    JsonObject line = lines.get(i).getAsJsonObject();
                    requested.merge(line.get("foodItemId").getAsLong(), line.get("quantity").getAsInt(), Integer::sum);
                }
            }
            long userId = user == null ? 0 : user.id();
            Order order = body.has("slotId")
                    ? orders.place(userId, body.get("slotId").getAsLong(), requested, payment, pickupName, requestId)
                    : orders.place(userId, LocalDate.parse(value(body, "pickupDate", "")),
                            requested, payment, pickupName, requestId);
            Json.send(response, 201, Map.of("order", order));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Order CSV placement failed.", e);
            Json.error(response, 500, "Could not place the order. Please retry.");
        } catch (RuntimeException e) {
            Json.error(response, 400, "The order request is incomplete or invalid.");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "STUDENT")) return;
        User user = ApiAccess.user(request);
        try {
            long id = parseId(request.getPathInfo());
            orders.cancel(id, user.id(), false, "Cancelled by student.");
            Json.send(response, 200, Map.of("message", "Order cancelled."));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Student order CSV cancellation failed.", e);
            Json.error(response, 500, "Could not cancel the order.");
        }
    }

    static long parseId(String path) {
        if (path == null || !path.matches("/\\d+")) throw new IllegalArgumentException();
        return Long.parseLong(path.substring(1));
    }

    private static String value(JsonObject body, String key, String fallback) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : fallback;
    }
}
