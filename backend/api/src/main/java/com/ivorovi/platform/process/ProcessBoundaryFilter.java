package com.ivorovi.platform.process;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before security and routing: a request for an endpoint this process does not serve gets a bare 404, exactly as if
 * the endpoint did not exist (no authentication challenge, no hint which process would serve it).
 */
public final class ProcessBoundaryFilter extends OncePerRequestFilter {

    private final ProcessRole role;

    public ProcessBoundaryFilter(ProcessRole role) {
        this.role = role;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!role.serves(path)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"code\":\"not_found\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
