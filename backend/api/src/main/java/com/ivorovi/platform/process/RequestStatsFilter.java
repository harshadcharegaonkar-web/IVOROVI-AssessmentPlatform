package com.ivorovi.platform.process;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Feeds {@link RequestStats} for API requests ({@code /api/**}); health and metrics probes are not counted. */
public final class RequestStatsFilter extends OncePerRequestFilter {

    private final RequestStats stats;

    public RequestStatsFilter(RequestStats stats) {
        this.stats = stats;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        stats.started();
        boolean error = true;
        try {
            chain.doFilter(request, response);
            error = response.getStatus() >= 500;
        } finally {
            stats.finished((System.nanoTime() - start) / 1_000_000, error);
        }
    }
}
