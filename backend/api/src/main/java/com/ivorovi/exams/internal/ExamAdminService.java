package com.ivorovi.exams.internal;

import com.ivorovi.exams.CandidateMfa;
import com.ivorovi.exams.ExamsProperties;
import com.ivorovi.notifications.NotificationService;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.crypto.FieldCipher;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.PortalDirectory;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff side of the exam foundations: portals (organization-wide, {@code exam.session.manage} tenant-wide), readiness
 * sessions and their single-use codes ({@code exam.session.manage} at the session's unit), check-ins and candidate accounts.
 */
@Service
public class ExamAdminService {

    public static final String SESSION_MANAGE = "exam.session.manage";
    static final String EMAIL_FIELD = "candidate_account.email";
    static final String CODE_PURPOSE = "session-code";
    private static final int MAX_CODES = 500;

    @Schema(name = "ExamPortal")
    public record Portal(UUID id, String portalKey, String name, String status, boolean selfRegistration, String mfaPolicy, String url,
                         long candidates, Instant createdAt, long version) {}

    @Schema(name = "ReadinessSession")
    public record Session(UUID id, UUID portalId, String portalName, UUID orgUnitId, String orgUnitName, String name, Instant startsAt, Instant endsAt,
                          String status, long codes, long codesUsed, long checkIns, long version) {}

    @Schema(name = "IssuedSessionCode")
    public record IssuedCode(UUID id, String code, String hint, Instant validFrom, Instant validUntil) {}

    @Schema(name = "SessionCode")
    public record Code(UUID id, String hint, Instant validFrom, Instant validUntil, String status, Instant consumedAt, String consumedByName,
                       String issuedForName) {}

    @Schema(name = "SessionCheckIn")
    public record CheckIn(UUID candidateId, String displayName, String email, Instant checkedInAt, Integer clockOffsetMs, Instant lastSeenAt, Long beats,
                          Long saves) {}

    @Schema(name = "VenueNetwork")
    public record VenueNetwork(String network, String label) {}

    @Schema(name = "CandidateAccount")
    public record Candidate(UUID id, String displayName, String email, String status, String portalName, Instant createdAt, Instant lastSeenAt) {}

    private final JdbcClient jdbc;
    private final Authz authz;
    private final AuditLog audit;
    private final FieldCipher cipher;
    private final PortalDirectory directory;
    private final CandidateMfa mfa;
    private final NotificationService notifications;
    private final ExamsProperties props;

    ExamAdminService(JdbcClient jdbc, Authz authz, AuditLog audit, FieldCipher cipher, PortalDirectory directory, CandidateMfa mfa,
                     NotificationService notifications, ExamsProperties props) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.audit = audit;
        this.cipher = cipher;
        this.directory = directory;
        this.mfa = mfa;
        this.notifications = notifications;
        this.props = props;
    }

    // ================================================================================================ portals

    @Transactional(readOnly = true)
    public List<Portal> portals() {
        if (!authz.hasAnywhere(SESSION_MANAGE)) throw new AccessDeniedException("Missing permission " + SESSION_MANAGE);
        return jdbc.sql("""
                SELECT p.*, (SELECT count(*) FROM candidate_account c WHERE c.portal_id = p.id) AS candidates FROM exam_portal p ORDER BY p.name""")
                .query(this::portalRow).list();
    }

    @Transactional
    public Portal createPortal(String key, String name, String mfaPolicy, boolean selfRegistration) {
        authz.require(SESSION_MANAGE, null);
        String k = key == null ? "" : key.trim().toLowerCase();
        if (!PortalDirectory.KEY.matcher(k).matches()) {
            throw ApiException.badRequest("invalid_portal_key", "Use 3–40 lower-case letters, digits or hyphens, starting with a letter or digit.");
        }
        UUID id = Ids.newId();
        try {
            jdbc.sql("INSERT INTO exam_portal (id, tenant_id, portal_key, name, mfa_policy, self_registration, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, TenantContext.requireTenantId(), k, name.trim(), mfaPolicy, selfRegistration, me()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("portal_key_taken", "This portal address is already in use.");
        }
        directory.evict(k);
        audit.recordSuccess("exam.portal.created", "exam_portal", id.toString(), Map.of("portalKey", k, "mfaPolicy", mfaPolicy,
                "selfRegistration", String.valueOf(selfRegistration)));
        return portal(id);
    }

    @Transactional
    public Portal updatePortal(UUID id, String name, String mfaPolicy, boolean selfRegistration, String status, long version) {
        authz.require(SESSION_MANAGE, null);
        Portal before = portal(id);
        int n = jdbc.sql("""
                UPDATE exam_portal SET name = ?, mfa_policy = ?, self_registration = ?, status = ?, updated_at = now(), version = version + 1
                WHERE id = ? AND version = ?""").params(name.trim(), mfaPolicy, selfRegistration, status, id, version).update();
        if (n == 0) throw ApiException.conflict("concurrent_modification", "This portal was changed by someone else. Reload and try again.");
        directory.evict(before.portalKey());
        audit.recordSuccess("exam.portal.updated", "exam_portal", id.toString(), Map.of("mfaPolicy", before.mfaPolicy() + "→" + mfaPolicy,
                "selfRegistration", before.selfRegistration() + "→" + selfRegistration, "status", before.status() + "→" + status));
        return portal(id);
    }

    Portal portal(UUID id) {
        return jdbc.sql("SELECT p.*, (SELECT count(*) FROM candidate_account c WHERE c.portal_id = p.id) AS candidates FROM exam_portal p WHERE p.id = ?")
                .param(id).query(this::portalRow).optional().orElseThrow(() -> ApiException.notFound("Exam portal"));
    }

    private Portal portalRow(ResultSet rs, int n) throws SQLException {
        String key = rs.getString("portal_key");
        return new Portal(rs.getObject("id", UUID.class), key, rs.getString("name"), rs.getString("status"), rs.getBoolean("self_registration"),
                rs.getString("mfa_policy"), props.examAppBaseUrl() + "/p/" + key, rs.getLong("candidates"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }

    // ================================================================================================ readiness sessions

    private static final String SESSION_SELECT = """
            SELECT s.*, p.name AS portal_name, ou.name AS unit_name,
                   (SELECT count(*) FROM session_code c WHERE c.session_id = s.id AND c.issued_for IS NULL) AS codes,
                   (SELECT count(*) FROM session_code c WHERE c.session_id = s.id AND c.issued_for IS NULL AND c.consumed_at IS NOT NULL) AS used,
                   (SELECT count(*) FROM check_in ci WHERE ci.session_id = s.id) AS check_ins
            FROM readiness_session s JOIN exam_portal p ON p.id = s.portal_id LEFT JOIN org_unit ou ON ou.id = s.org_unit_id""";

    @Transactional(readOnly = true)
    public List<Session> sessions() {
        if (!authz.hasAnywhere(SESSION_MANAGE)) throw new AccessDeniedException("Missing permission " + SESSION_MANAGE);
        return jdbc.sql(SESSION_SELECT + " ORDER BY s.starts_at DESC LIMIT 200").query(this::sessionRow).list().stream()
                .filter(s -> authz.hasInOrgUnit(SESSION_MANAGE, s.orgUnitId())).toList();
    }

    @Transactional(readOnly = true)
    public Session session(UUID id) {
        Session s = jdbc.sql(SESSION_SELECT + " WHERE s.id = ?").param(id).query(this::sessionRow).optional()
                .orElseThrow(() -> ApiException.notFound("Session"));
        if (!authz.hasInOrgUnit(SESSION_MANAGE, s.orgUnitId())) throw ApiException.notFound("Session");
        return s;
    }

    @Transactional
    public Session createSession(UUID portalId, UUID orgUnitId, String name, Instant startsAt, Instant endsAt) {
        authz.require(SESSION_MANAGE, orgUnitId);
        if (!endsAt.isAfter(startsAt)) throw ApiException.badRequest("invalid_window", "The session must end after it starts.");
        if (Duration.between(startsAt, endsAt).toHours() > 24) throw ApiException.badRequest("invalid_window", "A session can last at most 24 hours.");
        Portal p = portal(portalId);
        UUID id = Ids.newId();
        jdbc.sql("INSERT INTO readiness_session (id, tenant_id, portal_id, org_unit_id, name, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, TenantContext.requireTenantId(), p.id(), orgUnitId, name.trim(), Timestamp.from(startsAt), Timestamp.from(endsAt), me()).update();
        audit.recordSuccess("exam.session.created", "readiness_session", id.toString(), Map.of("portal", p.portalKey(), "startsAt", startsAt.toString(),
                "endsAt", endsAt.toString()));
        return session(id);
    }

    @Transactional
    public Session cancelSession(UUID id) {
        Session s = session(id);
        jdbc.sql("UPDATE readiness_session SET status = 'CANCELLED', version = version + 1 WHERE id = ?").param(id).update();
        int revoked = jdbc.sql("UPDATE session_code SET revoked_at = now() WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL").param(id).update();
        audit.recordSuccess("exam.session.cancelled", "readiness_session", id.toString(), Map.of("codesRevoked", String.valueOf(revoked)));
        return session(s.id());
    }

    /**
     * Generates single-use codes for a session (proctor-released or per candidate, Q-05 still open). The plain codes are
     * returned once and never stored; the window defaults to 30 minutes before the start until the end.
     */
    @Transactional
    public List<IssuedCode> generateCodes(UUID sessionId, int count, Instant validFrom, Instant validUntil) {
        Session s = session(sessionId);
        if (!"SCHEDULED".equals(s.status())) throw ApiException.conflict("session_cancelled", "This session was cancelled.");
        if (count < 1 || count > MAX_CODES) throw ApiException.badRequest("invalid_count", "Generate between 1 and " + MAX_CODES + " codes at a time.");
        Instant from = validFrom == null ? s.startsAt().minus(Duration.ofMinutes(30)) : validFrom;
        Instant until = validUntil == null ? s.endsAt() : validUntil;
        if (!until.isAfter(from)) throw ApiException.badRequest("invalid_window", "A code must be valid for some time.");
        if (until.isAfter(s.endsAt().plus(Duration.ofHours(1)))) throw ApiException.badRequest("invalid_window", "Codes cannot outlive the session by more than an hour.");
        UUID tenant = TenantContext.requireTenantId();
        List<IssuedCode> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String code = SessionCodes.generate();
            UUID id = Ids.newId();
            jdbc.sql("INSERT INTO session_code (id, tenant_id, session_id, code_hash, hint, valid_from, valid_until, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, tenant, sessionId, codeHash(tenant, code), SessionCodes.hint(code), Timestamp.from(from), Timestamp.from(until), me()).update();
            out.add(new IssuedCode(id, SessionCodes.format(code), SessionCodes.hint(code), from, until));
        }
        audit.recordSuccess("exam.session_codes.generated", "readiness_session", sessionId.toString(), Map.of("count", String.valueOf(count),
                "validFrom", from.toString(), "validUntil", until.toString()));
        return out;
    }

    String codeHash(UUID tenant, String normalizedCode) {
        return cipher.blindIndex(tenant + ":" + normalizedCode, CODE_PURPOSE);
    }

    @Transactional(readOnly = true)
    public List<Code> codes(UUID sessionId) {
        session(sessionId);
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("""
                SELECT c.*, ca.display_name AS used_name, cb.display_name AS for_name FROM session_code c
                LEFT JOIN candidate_account ca ON ca.id = c.consumed_by LEFT JOIN candidate_account cb ON cb.id = c.issued_for
                WHERE c.session_id = ? ORDER BY c.created_at, c.id""").param(sessionId).query((rs, n) -> {
                    Instant now = Instant.now();
                    String status = rs.getTimestamp("consumed_at") != null ? "USED" : rs.getTimestamp("revoked_at") != null ? "REVOKED"
                            : rs.getTimestamp("locked_at") != null ? "LOCKED"
                            : rs.getTimestamp("valid_until").toInstant().isBefore(now) ? "EXPIRED"
                            : rs.getTimestamp("valid_from").toInstant().isAfter(now) ? "NOT_YET_VALID" : "AVAILABLE";
                    return new Code(rs.getObject("id", UUID.class), rs.getString("hint"), rs.getTimestamp("valid_from").toInstant(),
                            rs.getTimestamp("valid_until").toInstant(), status, ts(rs, "consumed_at"), rs.getString("used_name"), rs.getString("for_name"));
                }).list();
    }

    @Transactional
    public void revokeCode(UUID sessionId, UUID codeId) {
        session(sessionId);
        int n = jdbc.sql("UPDATE session_code SET revoked_at = now() WHERE id = ? AND session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL")
                .params(codeId, sessionId).update();
        if (n == 0) throw ApiException.conflict("code_not_revocable", "This code was already used or revoked.");
        audit.recordSuccess("exam.session_code.revoked", "session_code", codeId.toString(), Map.of("session", sessionId.toString()));
    }

    // ================================================================================================ venue networks (KI-66 L5)

    private static final int MAX_VENUE_NETWORKS = 50;

    @Transactional(readOnly = true)
    public List<VenueNetwork> venueNetworks(UUID sessionId) {
        session(sessionId);
        return jdbc.sql("SELECT network::text, label FROM exam_venue_network WHERE session_id = ? ORDER BY network").param(sessionId)
                .query((rs, n) -> new VenueNetwork(rs.getString(1), rs.getString(2))).list();
    }

    /**
     * Replaces the session's venue networks: check-ins from these addresses are never delayed by the address layer (it only
     * reports). At most 50 networks, none wider than /16 (IPv4) or /48 (IPv6).
     */
    @Transactional
    public List<VenueNetwork> replaceVenueNetworks(UUID sessionId, List<VenueNetwork> networks) {
        Session s = session(sessionId);
        if (!"SCHEDULED".equals(s.status())) throw ApiException.conflict("session_cancelled", "This session was cancelled.");
        if (networks.size() > MAX_VENUE_NETWORKS) throw ApiException.badRequest("too_many_networks", "At most " + MAX_VENUE_NETWORKS + " venue networks per session.");
        List<String> normalized = new ArrayList<>();
        for (VenueNetwork v : networks) {
            String n = v.network() == null ? "" : v.network().trim();
            String canonical;
            try {
                canonical = jdbc.sql("""
                        SELECT CASE WHEN (family(CAST(? AS cidr)) = 4 AND masklen(CAST(? AS cidr)) >= 16) OR (family(CAST(? AS cidr)) = 6 AND masklen(CAST(? AS cidr)) >= 48)
                               THEN CAST(CAST(? AS cidr) AS text) END""").params(n, n, n, n, n).query(String.class).single();
            } catch (RuntimeException e) {
                throw ApiException.badRequest("invalid_network", "Not a network address: " + (n.length() > 50 ? n.substring(0, 50) : n));
            }
            if (canonical == null) throw ApiException.badRequest("network_too_wide", "Use networks no wider than /16 (IPv4) or /48 (IPv6): " + n);
            if (!normalized.contains(canonical)) normalized.add(canonical);
        }
        jdbc.sql("DELETE FROM exam_venue_network WHERE session_id = ?").param(sessionId).update();
        UUID tenant = TenantContext.requireTenantId();
        for (int i = 0; i < networks.size(); i++) {
            VenueNetwork v = networks.get(i);
            String label = v.label() == null || v.label().isBlank() ? null : v.label().trim();
            jdbc.sql("""
                    INSERT INTO exam_venue_network (id, tenant_id, session_id, network, label, created_by) VALUES (?, ?, ?, CAST(? AS cidr), ?, ?)
                    ON CONFLICT (tenant_id, session_id, network) DO NOTHING""")
                    .params(Ids.newId(), tenant, sessionId, v.network().trim(), label == null ? null : label.length() > 100 ? label.substring(0, 100) : label, me()).update();
        }
        audit.recordSuccess("exam.session.venue_networks.updated", "readiness_session", sessionId.toString(), Map.of("networks", String.join(",", normalized)));
        return venueNetworks(sessionId);
    }

    @Transactional(readOnly = true)
    public List<CheckIn> checkIns(UUID sessionId) {
        session(sessionId);
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("""
                SELECT ci.candidate_id, ca.display_name, ca.email_enc, ci.checked_in_at, ci.clock_offset_ms, p.last_seen_at, p.beats, p.saves
                FROM check_in ci JOIN candidate_account ca ON ca.id = ci.candidate_id
                LEFT JOIN delivery_presence p ON p.candidate_id = ci.candidate_id AND p.session_id = ci.session_id
                WHERE ci.session_id = ? ORDER BY ci.checked_in_at""").param(sessionId).query((rs, n) -> new CheckIn(rs.getObject(1, UUID.class),
                        rs.getString(2), cipher.decrypt(rs.getString(3), EMAIL_FIELD, tenant), rs.getTimestamp(4).toInstant(),
                        (Integer) rs.getObject(5), ts(rs, "last_seen_at"), (Long) rs.getObject(7), (Long) rs.getObject(8))).list();
    }

    private Session sessionRow(ResultSet rs, int n) throws SQLException {
        return new Session(rs.getObject("id", UUID.class), rs.getObject("portal_id", UUID.class), rs.getString("portal_name"),
                rs.getObject("org_unit_id", UUID.class), rs.getString("unit_name"), rs.getString("name"), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(), rs.getString("status"), rs.getLong("codes"), rs.getLong("used"), rs.getLong("check_ins"),
                rs.getLong("version"));
    }

    // ================================================================================================ candidates

    @Transactional(readOnly = true)
    public List<Candidate> candidates() {
        authz.require(SESSION_MANAGE, null);
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.sql("""
                SELECT c.id, c.display_name, c.email_enc, c.status, p.name AS portal, c.created_at, c.last_seen_at
                FROM candidate_account c JOIN exam_portal p ON p.id = c.portal_id ORDER BY c.created_at DESC LIMIT 500""")
                .query((rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), cipher.decrypt(rs.getString(3), EMAIL_FIELD, tenant),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), ts(rs, "last_seen_at"))).list();
    }

    @Transactional
    public Candidate setCandidateStatus(UUID id, boolean active) {
        authz.require(SESSION_MANAGE, null);
        int n = jdbc.sql("UPDATE candidate_account SET status = ? WHERE id = ?").params(active ? "ACTIVE" : "SUSPENDED", id).update();
        if (n == 0) throw ApiException.notFound("Candidate");
        audit.recordSuccess(active ? "exam.candidate.reactivated" : "exam.candidate.suspended", "candidate_account", id.toString(), Map.of());
        return candidates().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Lost authenticator (recovery codes also lost): removes the candidate's second factors at the identity provider, signs
     * out all of the candidate's sessions and tells the candidate by email. The candidate must enrol again at next sign-in
     * when the portal requires MFA.
     */
    @Transactional
    public void resetCandidateMfa(UUID id, String reason) {
        authz.require(SESSION_MANAGE, null);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("reason_required", "Record why the second factor is being reset.");
        UUID tenant = TenantContext.requireTenantId();
        record Row(String subject, String email) {}
        Row r = jdbc.sql("SELECT subject, email_enc FROM candidate_account WHERE id = ?").param(id)
                .query((rs, n) -> new Row(rs.getString(1), cipher.decrypt(rs.getString(2), EMAIL_FIELD, tenant))).optional()
                .orElseThrow(() -> ApiException.notFound("Candidate"));
        mfa.reset(r.subject());
        notifications.email(r.email(), "exams", "Your exam sign-in security was reset",
                "Your institution reset the second sign-in factor (authenticator app and recovery codes) of your exam account, and all of your "
                        + "sessions were signed out. If you did not ask for this, contact your institution's examination office immediately.\n\n"
                        + "Sign in again at " + props.examAppBaseUrl() + " to set up a new authenticator app.");
        audit.recordSuccess("exam.candidate.mfa_reset", "candidate_account", id.toString(), Map.of("reason", reason.length() > 300 ? reason.substring(0, 300) : reason));
    }

    // ================================================================================================ helpers

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
