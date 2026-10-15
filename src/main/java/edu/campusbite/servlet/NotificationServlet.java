package edu.campusbite.servlet;

import edu.campusbite.model.Notification;
import edu.campusbite.model.User;
import edu.campusbite.util.ApiAccess;
import edu.campusbite.util.CsvDataStore;
import edu.campusbite.util.CsvDataStore.Record;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@WebServlet("/api/notifications/*")
public class NotificationServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        User user = ApiAccess.user(request);
        try {
            List<Notification> notifications = CsvDataStore.read(state -> state.all("notification").stream()
                    .filter(row -> row.longValue("userId") == user.id())
                    .sorted(Comparator.comparing((Record row) -> row.get("createdAt")).reversed())
                    .limit(50)
                    .map(row -> new Notification(row.longValue("id"), row.longValue("orderId"),
                            row.get("message"), row.booleanValue("isRead"), row.get("createdAt")))
                    .toList());
            Json.send(response, 200, Map.of("notifications", notifications));
        } catch (IOException e) {
            getServletContext().log("Notification CSV read failed.", e);
            Json.error(response, 500, "Notifications are temporarily unavailable.");
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        User user = ApiAccess.user(request);
        String path = request.getPathInfo();
        if (path == null || !path.matches("/\\d+/read")) {
            Json.error(response, 404, "Endpoint not found.");
            return;
        }
        try {
            long id = Long.parseLong(path.substring(1, path.length() - "/read".length()));
            boolean updated = CsvDataStore.update(state -> {
                Record notification = state.find("notification", id);
                if (notification == null || notification.longValue("userId") != user.id()) return false;
                notification.set("isRead", "true");
                return true;
            });
            if (!updated) Json.error(response, 404, "Notification not found.");
            else Json.send(response, 200, Map.of("message", "Notification marked as read."));
        } catch (IOException e) {
            getServletContext().log("Notification CSV update failed.", e);
            Json.error(response, 500, "Could not update the notification.");
        }
    }
}
