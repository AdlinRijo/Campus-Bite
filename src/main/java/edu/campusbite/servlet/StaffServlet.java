package edu.campusbite.servlet;

import com.google.gson.JsonObject;
import edu.campusbite.dao.FoodDao;
import edu.campusbite.model.Order;
import edu.campusbite.service.OrderService;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/staff/*")
public class StaffServlet extends HttpServlet {
    private final OrderService orders = new OrderService();
    private final FoodDao foods = new FoodDao();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "STAFF", "ADMIN")) return;
        String path = request.getPathInfo();
        if (path == null || !path.startsWith("/orders")) {
            Json.error(response, 404, "Endpoint not found.");
            return;
        }
        try {
            Json.send(response, 200, Map.of("orders", orders.list(0, true, request.getParameter("status"))));
        } catch (IOException e) {
            getServletContext().log("Staff order CSV read failed.", e);
            Json.error(response, 500, "The kitchen order list is temporarily unavailable.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "STAFF", "ADMIN")) return;
        try {
            String path = request.getPathInfo();
            if (path != null && path.matches("/food/\\d+")) {
                long id = Long.parseLong(path.split("/")[2]);
                JsonObject body = Json.body(request);
                if (!body.has("stockQuantity") || !body.has("available"))
                    throw new IllegalArgumentException("Provide a stock quantity and availability.");
                foods.updateStockAndAvailability(id, body.get("stockQuantity").getAsInt(),
                        body.get("available").getAsBoolean());
                Json.send(response, 200, Map.of("message", "Menu availability updated."));
                return;
            }
            if (path == null || !path.matches("/orders/\\d+/status")) {
                Json.error(response, 404, "Endpoint not found.");
                return;
            }
            long id = Long.parseLong(path.split("/")[2]);
            JsonObject body = Json.body(request);
            if (!body.has("status")) throw new IllegalArgumentException("Provide a status.");
            orders.changeStatus(id, body.has("status") ? body.get("status").getAsString() : "",
                    body.has("reason") ? body.get("reason").getAsString() : null);
            Json.send(response, 200, Map.of("message", "Order status updated."));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Staff CSV update failed.", e);
            Json.error(response, 500, "Could not update the order.");
        } catch (RuntimeException e) {
            Json.error(response, 400, "The staff update request is invalid.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "STAFF", "ADMIN")) return;
        try {
            if (!"/collect".equals(request.getPathInfo())) {
                Json.error(response, 404, "Endpoint not found.");
                return;
            }
            JsonObject body = Json.body(request);
            String token = body.has("tokenNumber") ? body.get("tokenNumber").getAsString().trim() : "";
            if (!token.matches("CB-\\d{8}-\\d{5,}")) {
                Json.error(response, 400, "Enter a valid CampusBite token.");
                return;
            }
            java.util.List<Order> matches = orders.list(0, true, "READY");
            Order match = matches.stream()
                    .filter(order -> token.equals(order.tokenNumber())).findFirst().orElse(null);
            if (match == null) {
                Json.error(response, 404, "No ready order was found for that token.");
                return;
            }
            orders.changeStatus(match.id(), "COLLECTED", null);
            Json.send(response, 200, Map.of("message", "Token verified. Order marked collected."));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Token CSV update failed.", e);
            Json.error(response, 500, "Could not verify the token.");
        }

    }
}
