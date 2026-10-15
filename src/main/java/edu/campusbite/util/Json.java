package edu.campusbite.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

public final class Json {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Json() {}

    public static JsonObject body(HttpServletRequest request) throws IOException {
        try {
            JsonObject body = GSON.fromJson(request.getReader(), JsonObject.class);
            if (body == null) throw new IllegalArgumentException("A JSON request body is required.");
            return body;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Request body must be valid JSON.");
        }
    }

    public static void send(HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        GSON.toJson(value, response.getWriter());
    }

    public static void error(HttpServletResponse response, int status, String message) throws IOException {
        send(response, status, Map.of("error", message));
    }
}
