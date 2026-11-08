package com.ivorovi.platform.process;

/**
 * What this process serves (P2-03, C-4). The same build runs as the administrative API ({@code ADMIN}) or as the exam
 * delivery API ({@code DELIVERY}); each refuses the other's endpoints with 404, so a delivery process never exposes a
 * staff endpoint and the staff API never serves candidates.
 */
public enum ProcessRole {
    ADMIN,
    DELIVERY;

    public static final String DELIVERY_PREFIX = "/api/v1/delivery/";

    /** True when this process serves the request path (health and metrics endpoints are served by both). */
    public boolean serves(String path) {
        if (path.startsWith("/actuator/health") || path.equals("/actuator/prometheus") || path.equals("/actuator/info")) return true;
        boolean delivery = path.startsWith(DELIVERY_PREFIX) || path.equals("/api/v1/delivery");
        return this == DELIVERY ? delivery : !delivery;
    }
}
