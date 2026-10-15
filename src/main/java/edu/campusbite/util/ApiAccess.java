package edu.campusbite.util;

import edu.campusbite.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

public final class ApiAccess {
    private ApiAccess() {}

    public static User user(HttpServletRequest request) {
        return (User) request.getAttribute("user");
    }

    public static boolean requireRole(HttpServletRequest request, HttpServletResponse response, String... roles)
            throws IOException {
        User user = user(request);
        if (user != null && Arrays.asList(roles).contains(user.role())) return true;
        Json.error(response, 403, "You do not have permission to perform this action.");
        return false;
    }
}
