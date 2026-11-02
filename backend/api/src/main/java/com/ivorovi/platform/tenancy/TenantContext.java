package com.ivorovi.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant on whose behalf the current thread is working.
 *
 * <p>Set only from a verified token (see {@code IvoroviJwtAuthenticationConverter}) or explicitly by platform jobs.
 * Every pooled connection borrowed while a context is bound is configured with {@code app.tenant_id}, which
 * PostgreSQL Row Level Security enforces. No context means no tenant rows are visible.
 *
 * <p>Important: switching context inside an open transaction does not affect the connection that transaction
 * already holds. Callers of {@link #callInTenant} must start a new transaction inside the callback.
 */
public final class TenantContext {

    private record State(UUID tenantId, boolean platform) {}

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> currentTenantId() {
        State s = CURRENT.get();
        return s == null ? Optional.empty() : Optional.ofNullable(s.tenantId());
    }

    public static UUID requireTenantId() {
        return currentTenantId().orElseThrow(() -> new MissingTenantContextException());
    }

    public static boolean isPlatformContext() {
        State s = CURRENT.get();
        return s != null && s.platform();
    }

    /** Binds the tenant for the rest of the request. Cleared by {@code TenantContextFilter}. */
    public static void bindRequestTenant(UUID tenantId) {
        CURRENT.set(new State(tenantId, false));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callInTenant(UUID tenantId, Supplier<T> work) {
        return callWith(new State(tenantId, false), work);
    }

    public static void runInTenant(UUID tenantId, Runnable work) {
        callWith(new State(tenantId, false), () -> { work.run(); return null; });
    }

    /** Runs platform-level work (tenant resolution, outbox publishing). Tenant-owned rows stay invisible. */
    public static <T> T callAsPlatform(Supplier<T> work) {
        return callWith(new State(null, true), work);
    }

    private static <T> T callWith(State state, Supplier<T> work) {
        State previous = CURRENT.get();
        CURRENT.set(state);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static final class MissingTenantContextException extends IllegalStateException {
        public MissingTenantContextException() {
            super("No tenant context bound to the current thread");
        }
    }
}
