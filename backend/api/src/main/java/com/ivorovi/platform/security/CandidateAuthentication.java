package com.ivorovi.platform.security;

import com.ivorovi.platform.audit.AuditActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A verified candidate-realm token (issuer and audience of the delivery boundary). Carries no staff permissions at all:
 * a candidate is never an {@link IvoroviPrincipal}. The organization is bound from the exam portal of the request path
 * (server-side configuration, Q-07), and {@link #link} attaches the candidate account once it is resolved there.
 */
public final class CandidateAuthentication extends AbstractAuthenticationToken implements AuditActor {

    public static final String AUTHORITY = "CANDIDATE";
    private final Jwt jwt;
    private volatile UUID candidateId;

    public CandidateAuthentication(Jwt jwt) {
        super(AuthorityUtils.createAuthorityList(AUTHORITY));
        this.jwt = jwt;
        setAuthenticated(true);
    }

    public static Optional<CandidateAuthentication> current() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof CandidateAuthentication c ? Optional.of(c) : Optional.empty();
    }

    public String subject() { return jwt.getSubject(); }

    public String email() { return jwt.getClaimAsString("email"); }

    public String name() {
        String n = jwt.getClaimAsString("name");
        return n == null || n.isBlank() ? null : n;
    }

    /** Authentication methods reported by the identity provider (e.g. {@code pwd}, {@code otp}); may be empty. */
    public List<String> amr() {
        List<String> amr = jwt.getClaimAsStringList("amr");
        return amr == null ? List.of() : amr;
    }

    public Jwt token() { return jwt; }

    public Optional<UUID> candidateId() { return Optional.ofNullable(candidateId); }

    public void link(UUID candidateId) { this.candidateId = candidateId; }

    @Override
    public Object getCredentials() { return ""; }

    @Override
    public Object getPrincipal() { return jwt.getSubject(); }

    @Override
    public UUID auditActorId() { return candidateId; }

    @Override
    public String auditActorSubject() { return "candidate:" + jwt.getSubject(); }
}
