package com.ivorovi.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class IvoroviAuthentication extends AbstractAuthenticationToken {

    private final IvoroviPrincipal principal;
    private final transient Jwt token;

    public IvoroviAuthentication(IvoroviPrincipal principal, Jwt token) {
        // Authorities are informational (role codes); authorization decisions use scoped grants via Authz.
        super(principal.roleCodes().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        this.principal = principal;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public IvoroviPrincipal getPrincipal() { return principal; }

    @Override
    public Jwt getCredentials() { return token; }

    @Override
    public String getName() { return principal.subject(); }

    @Override
    public boolean equals(Object o) { return super.equals(o); }

    @Override
    public int hashCode() { return super.hashCode(); }

}
