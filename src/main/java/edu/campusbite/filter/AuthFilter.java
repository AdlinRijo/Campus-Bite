package edu.campusbite.filter;

import edu.campusbite.model.User;
import edu.campusbite.util.Json;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;

@WebFilter("/api/*")
public class AuthFilter implements Filter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        String uri = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        boolean publicEndpoint = ("GET".equals(method) && ("/api/food".equals(uri) || "/api/slots".equals(uri)
                || "/api/auth/csrf".equals(uri) || "/api/auth/me".equals(uri)));
        if (publicEndpoint) {
            chain.doFilter(request, response);
            return;
        }

        boolean authEntry = "POST".equals(method)
                && ("/api/auth/login".equals(uri) || "/api/auth/register".equals(uri));
        boolean guestOrderPlacement = "POST".equals(method) && "/api/orders".equals(uri);
        HttpSession session = request.getSession(false);
        User user = session == null ? null : (User) session.getAttribute("user");
        if (user == null && !authEntry && !guestOrderPlacement) {
            Json.error(response, 401, "Please sign in to continue.");
            return;
        }
        if (!SAFE_METHODS.contains(method)) {
            String expected = session == null ? null : (String) session.getAttribute("csrf");
            String supplied = request.getHeader("X-CSRF-Token");
            if (expected == null || !expected.equals(supplied)) {
                Json.error(response, 403, "Invalid or missing CSRF token.");
                return;
            }
        }
        if (user != null) request.setAttribute("user", user);
        chain.doFilter(request, response);
    }
}
