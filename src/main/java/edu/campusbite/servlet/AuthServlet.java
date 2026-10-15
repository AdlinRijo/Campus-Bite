package edu.campusbite.servlet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import edu.campusbite.model.User;
import edu.campusbite.service.AuthService;
import edu.campusbite.util.Json;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@WebServlet("/api/auth/*")
public class AuthServlet extends HttpServlet {
    private final AuthService auth = new AuthService();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if ("/csrf".equals(path)) {
            HttpSession session = request.getSession(true);
            String token = (String) session.getAttribute("csrf");
            if (token == null) {
                token = UUID.randomUUID().toString();
                session.setAttribute("csrf", token);
            }
            Json.send(response, 200, Map.of("csrfToken", token));
        } else if ("/me".equals(path)) {
            HttpSession session = request.getSession(false);
            User user = session == null ? null : (User) session.getAttribute("user");
            Json.send(response, 200, Collections.singletonMap("user", user));
        } else {
            Json.error(response, 404, "Endpoint not found.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        try {
            if ("/register".equals(path)) {
                JsonObject body = Json.body(request);
                User user = auth.register(string(body, "name"), string(body, "email"), string(body, "password"));
                signIn(request, user);
                Json.send(response, 201, Map.of("user", user, "csrfToken", request.getSession().getAttribute("csrf")));
            } else if ("/login".equals(path)) {
                JsonObject body = Json.body(request);
                User user = auth.login(string(body, "email"), string(body, "password"));
                if (user == null) {
                    Json.error(response, 401, "Email or password is incorrect.");
                    return;
                }
                signIn(request, user);
                Json.send(response, 200, Map.of("user", user, "csrfToken", request.getSession().getAttribute("csrf")));
            } else if ("/logout".equals(path)) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                Json.send(response, 200, Map.of("message", "Signed out."));
            } else {
                Json.error(response, 404, "Endpoint not found.");
            }
        } catch (IllegalArgumentException e) {
            Json.error(response, 400, e.getMessage());
        } catch (edu.campusbite.util.CsvDataStore.ConflictException e) {
            Json.error(response, 409, e.getMessage());
        } catch (IOException e) {
            getServletContext().log("Authentication CSV operation failed.", e);
            Json.error(response, 500, "The account service is temporarily unavailable.");
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Json.error(response, 400, "The authentication request is invalid.");
        }
    }

    private static String string(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Authentication fields must be text.");
        return value.getAsString();
    }

    private static void signIn(HttpServletRequest request, User user) {
        HttpSession old = request.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute("user", user);
        session.setAttribute("csrf", UUID.randomUUID().toString());
    }
}
