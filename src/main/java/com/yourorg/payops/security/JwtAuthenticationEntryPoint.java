package com.yourorg.payops.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Rejects unauthenticated requests with HTTP 401 Unauthorized in JSON format.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        String detail = (String) request.getAttribute("jwt_exception");
        String message = (detail != null) ? detail : "Authentication is required to access this resource";
        response.getWriter().write(String.format("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}", message));
    }
}
