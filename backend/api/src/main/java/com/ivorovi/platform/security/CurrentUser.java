package com.ivorovi.platform.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<IvoroviPrincipal> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof IvoroviAuthentication ia ? Optional.of(ia.getPrincipal()) : Optional.empty();
    }

    public static IvoroviPrincipal require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated Ivorovi principal"));
    }
}
