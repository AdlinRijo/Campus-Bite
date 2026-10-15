package edu.campusbite.servlet;

import com.google.gson.JsonObject;
import edu.campusbite.dao.FoodDao;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

@WebServlet("/api/food/*")
public class FoodServlet extends HttpServlet {
    private final FoodDao foods = new FoodDao();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            Json.send(response, 200, Map.of("items", foods.list(request.getParameter("search"),
                request.getParameter("category"), request.getParameter("sort"))));
        } catch (IOException e) {
            getServletContext().log("Food menu CSV query failed.", e);
            Json.error(response, 500, "The menu is temporarily unavailable.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        save(request, response, null);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        Long id = itemId(request, response);
        if (id != null) save(request, response, id);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        Long id = itemId(request, response);
        if (id == null) return;
        try {
            foods.delete(id);
            Json.send(response, 200, Map.of("message", "Menu item deleted."));
        } catch (IllegalArgumentException e) {
            Json.error(response, 404, e.getMessage());
        } catch (edu.campusbite.util.CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Food item CSV deletion failed.", e);
            Json.error(response, 500, "Could not delete the menu item.");
        }
    }

    private void save(HttpServletRequest request, HttpServletResponse response, Long id) throws IOException {
        try {
            JsonObject body = Json.body(request);
            String name = text(body, "name");
            String category = text(body, "category");
            String image = text(body, "imageUrl");
            String description = body.has("description") ? text(body, "description") : "";
            BigDecimal price = new BigDecimal(text(body, "price"));
            int stock = Integer.parseInt(text(body, "stockQuantity"));
            boolean available = !body.has("available") || body.get("available").getAsBoolean();
            if (name.isBlank() || name.length() > 120 || category.isBlank() || category.length() > 60
                    || price.signum() < 0 || stock < 0 || description.length() > 500 || image.length() > 500) {
                throw new IllegalArgumentException("Check the menu item fields and try again.");
            }
            long savedId = foods.save(id, name.trim(), description, category.trim(), price, stock, image, available);
            Json.send(response, id == null ? 201 : 200, Map.of("id", savedId, "message", "Menu item saved."));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage() == null ? "Invalid menu item data." : e.getMessage());
        } catch (edu.campusbite.util.CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Food item CSV save failed.", e);
            Json.error(response, 500, "Could not save the menu item.");
        } catch (RuntimeException e) {
            Json.error(response, 400, "The menu item request is invalid.");
        }
    }

    private static String text(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : "";
    }

    private static Long itemId(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = request.getPathInfo();
            if (path == null || !path.matches("/\\d+")) throw new NumberFormatException();
            return Long.parseLong(path.substring(1));
        } catch (NumberFormatException e) {
            Json.error(response, 400, "A valid food item id is required.");
            return null;
        }
    }
}
