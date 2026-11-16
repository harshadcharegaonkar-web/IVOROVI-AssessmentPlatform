package com.ivorovi.platform.security;

import com.ivorovi.platform.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the organization's staff session policy ({@link StaffSessionGuard}) to every authenticated staff API call
 * (bearer tokens of the staff realm). API clients (machine credentials) are not sessions and are not affected. Refusals:
 * {@code 403 mfa_required}, {@code 401 session_idle_timeout}, {@code 401 session_expired}, {@code 401 session_untracked}.
 */
public class StaffSessionPolicyFilter extends OncePerRequestFilter {

    private final StaffSessionGuard guard;
    private final ProblemResponses problems;

    public StaffSessionPolicyFilter(StaffSessionGuard guard, ProblemResponses problems) {
        this.guard = guard;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof IvoroviAuthentication a && a.getCredentials() != null) {
            Jwt jwt = a.getCredentials();
            IvoroviPrincipal p = a.getPrincipal();
            List<String> amr = jwt.getClaimAsStringList("amr");
            Instant authTime = jwt.getClaimAsInstant("auth_time");
            Optional<StaffSessionGuard.Refusal> refusal = guard.check(p.tenantId(), p.userId(), p.subject(), jwt.getClaimAsString("sid"), authTime,
                    amr == null ? List.of() : amr);
            if (refusal.isPresent()) {
                switch (refusal.get()) {
                    case MFA_REQUIRED -> problems.write(response, HttpStatus.FORBIDDEN, "mfa_required",
                            "Your organization requires an authenticator app. Sign in again to set it up and use it.");
                    case SESSION_IDLE -> unauthorized(response, "session_idle_timeout", "Your session ended after a period of inactivity. Sign in again.");
                    case SESSION_EXPIRED -> unauthorized(response, "session_expired", "Your session reached its maximum length. Sign in again.");
                    case SESSION_UNTRACKED -> unauthorized(response, "session_untracked", "This sign-in cannot be checked against your organization's session limits. Sign in again.");
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String code, String detail) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        problems.write(response, HttpStatus.UNAUTHORIZED, code, detail);
    }
}
