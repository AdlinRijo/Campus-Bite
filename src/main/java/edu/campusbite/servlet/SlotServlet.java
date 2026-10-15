package edu.campusbite.servlet;

import com.google.gson.JsonObject;
import edu.campusbite.dao.SlotDao;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

@WebServlet("/api/slots")
public class SlotServlet extends HttpServlet {
    private final SlotDao slots = new SlotDao();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            LocalDate date = request.getParameter("date") == null ? LocalDate.now()
                    : LocalDate.parse(request.getParameter("date"));
            if (date.isBefore(LocalDate.now()) || date.isAfter(LocalDate.now().plusDays(30))) {
                Json.error(response, 400, "Pickup date must be within the next 30 days.");
                return;
            }
            Json.send(response, 200, Map.of("slots", slots.list(date)));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, "Enter a valid pickup date.");
        } catch (IOException e) {
            getServletContext().log("Pickup slot CSV query failed.", e);
            Json.error(response, 500, "Pickup slots are temporarily unavailable.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!ApiAccess.requireRole(request, response, "ADMIN")) return;
        try {
            JsonObject body = Json.body(request);
            LocalDate date = LocalDate.parse(value(body, "date"));
            String start = value(body, "startTime");
            String end = value(body, "endTime");
            int capacity = Integer.parseInt(value(body, "capacity"));
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            if (date.isBefore(LocalDate.now()) || capacity < 1 || capacity > 1000
                    || !startTime.isBefore(endTime)
                    || (date.equals(LocalDate.now()) && !startTime.isAfter(LocalTime.now()))
                    || !slots.withinOperatingHours(startTime, endTime))
                throw new IllegalArgumentException("Check the date, times, and capacity.");
            Json.send(response, 201, Map.of("id", slots.create(date, start, end, capacity)));
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, "Check the date, times, and capacity.");
        } catch (edu.campusbite.util.CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Pickup slot CSV creation failed.", e);
            Json.error(response, 500, "Could not create the pickup slot.");
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Json.error(response, 400, "The pickup slot request is invalid.");
        }
    }

    private static String value(JsonObject body, String field) {
        return body.has(field) && !body.get(field).isJsonNull() ? body.get(field).getAsString() : "";
    }
}
