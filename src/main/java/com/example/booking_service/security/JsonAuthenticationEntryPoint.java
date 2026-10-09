package com.example.booking_service.security;

import tools.jackson.databind.ObjectMapper;
import com.example.booking_service.exception.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Replaces Spring Security's default (empty-body, often 403) rejection with a
 * clear 401 + JSON body whenever an unauthenticated request hits an endpoint
 * that requires a valid JWT issued by auth-service.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = new ErrorResponse(
                "Authentication required", "UNAUTHENTICATED", "A valid bearer token is required");
        objectMapper.writeValue(response.getWriter(), error);
    }
}
