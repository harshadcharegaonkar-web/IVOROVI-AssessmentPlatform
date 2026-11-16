package com.ivorovi.exams.internal;

import com.ivorovi.platform.security.CandidateLogoutTokenDecoder;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KI-79 (Day 7): immediate end of candidate sessions. The candidate realm ends a session (password reset, which always
 * signs out the other devices; an administrator; the guard) and, inside that same operation, posts an OIDC back-channel
 * logout token to the delivery API. The session id is recorded here, and every delivery request carrying a token of that
 * session is refused from then on, although the token has not expired. {@link RecoveryGuard} remains as the backstop for a
 * notification that could not be delivered. Nothing identifying is logged: counts only.
 */
@Service
public class CandidateSessions {

    private static final Logger log = LoggerFactory.getLogger(CandidateSessions.class);

    private final JdbcClient jdbc;
    private final CandidateLogoutTokenDecoder decoder;
    private final AtomicLong received = new AtomicLong();

    CandidateSessions(JdbcClient jdbc, CandidateLogoutTokenDecoder decoder) {
        this.jdbc = jdbc;
        this.decoder = decoder;
    }

    /** Verifies a logout token and records its session as ended; false when the token is not valid (nothing recorded). */
    @Transactional
    public boolean logout(String logoutToken) {
        Jwt jwt;
        try {
            jwt = decoder.delegate().decode(logoutToken);
        } catch (JwtException | IllegalArgumentException invalid) {
            log.warn("Back-channel logout refused: the logout token is not valid ({})", invalid.getMessage());
            return false;
        }
        jdbc.sql("INSERT INTO candidate_ended_session (sid, subject) VALUES (?, ?) ON CONFLICT (sid) DO NOTHING")
                .params(jwt.getClaimAsString("sid"), jwt.getSubject()).update();
        log.info("Back-channel logout: a candidate session ended ({} since start)", received.incrementAndGet());
        return true;
    }

    /** True when the identity provider reported this session as ended. */
    boolean ended(String sid) {
        if (sid == null) return false;
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM candidate_ended_session WHERE sid = ?)").param(sid).query(Boolean.class).single();
    }

    /**
     * KI-79 / QA-D6-4: true when a password reset of this identity ended the token's session (the backstop record of
     * {@link RecoveryGuard}, per identity, valid in every organization).
     */
    boolean revokedByReset(String subject, java.time.Instant issuedAt, String sid) {
        record R(java.sql.Timestamp before, String kept) {}
        return jdbc.sql("SELECT revoked_before, kept_session_id FROM candidate_identity_revocation WHERE subject = ?").param(subject)
                .query((rs, n) -> new R(rs.getTimestamp(1), rs.getString(2))).optional()
                .map(r -> CandidateService.revoked(issuedAt, sid, r.before().toInstant(), r.kept())).orElse(false);
    }

    /** Records a reset for the identity (forward only; the kept session follows the newest reset, QA-D6-3). */
    void recordReset(String subject, java.time.Instant at, String keptSession) {
        jdbc.sql("""
                INSERT INTO candidate_identity_revocation (subject, revoked_before, kept_session_id) VALUES (?, ?, ?)
                ON CONFLICT (subject) DO UPDATE SET
                    kept_session_id = CASE WHEN EXCLUDED.revoked_before >= candidate_identity_revocation.revoked_before
                                           THEN EXCLUDED.kept_session_id ELSE candidate_identity_revocation.kept_session_id END,
                    revoked_before = greatest(candidate_identity_revocation.revoked_before, EXCLUDED.revoked_before),
                    updated_at = now()""").params(subject, java.sql.Timestamp.from(at), keptSession).update();
    }

    /** Ended sessions are kept two days (their tokens expire within minutes; the database refuses earlier deletion). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void purge() {
        jdbc.sql("DELETE FROM candidate_ended_session WHERE ended_at < now() - interval '2 days 1 minute'").update();
    }

}
