package com.ivorovi.identity.internal;

import com.ivorovi.identity.IdentityProvisioner;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.mail.EmailMessage;
import com.ivorovi.platform.mail.EmailSender;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.PrincipalCache;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations: single-use, expiring tokens (256 bits). Only the SHA-256 hash is stored; the token travels once,
 * in the email link fragment ({@code /invitation#token=…}, so it never reaches web-server or proxy logs). Neither
 * logs nor audit details ever contain it. Re-sending replaces the token, so earlier links stop working.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public enum State { PENDING, EXPIRED, ACCEPTED, REVOKED }

    @Schema(name = "Invitation")
    public record InvitationView(UUID id, UUID userId, String email, String userName, State status, Instant createdAt,
                                 Instant expiresAt, Instant lastSentAt, int sendCount, String deliveryStatus,
                                 Instant acceptedAt, Instant revokedAt, String invitedByName, long version) {}

    /** A freshly issued token; deliver it with {@link #deliver} after the transaction commits. */
    public record Issued(UUID invitationId, String email, String givenName, String token, Instant expiresAt) {
        @Override public String toString() { return "Issued[" + invitationId + "]"; }   // never print the token
    }

    @Schema(name = "InvitationLookup")
    public record Lookup(String organizationName, String email, String givenName, Instant expiresAt, boolean existingLogin) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Outbox outbox;
    private final EmailSender mail;
    private final IdentityProperties props;
    private final IdentityProvisioner idp;
    private final PeopleScope scope;
    private final PrincipalCache principals;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    InvitationService(JdbcClient jdbc, AuditLog audit, Outbox outbox, EmailSender mail, IdentityProperties props,
                      IdentityProvisioner idp, PeopleScope scope, PrincipalCache principals,
                      org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.mail = mail;
        this.props = props;
        this.idp = idp;
        this.scope = scope;
        this.principals = principals;
    }

    // ------------------------------------------------------------------------------------------- administration

    @Transactional(readOnly = true)
    public UserService.Page<InvitationView> list(State state, String q, int page, int size) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (state != null) { where.append(" AND ").append(STATE_SQL).append(" = ?"); params.add(state.name()); }
        if (q != null && !q.isBlank()) {
            where.append(" AND (i.email ILIKE ? OR u.display_name ILIKE ?)");
            params.add(UserService.like(q)); params.add(UserService.like(q));
        }
        if (!scope.appendVisibility(UserService.READ, PeopleScope.USER_UNITS_OF_U, where, params)) return new UserService.Page<>(List.of(), 0);
        long total = jdbc.sql("SELECT count(*) FROM user_invitation i JOIN user_account u ON u.id = i.user_account_id" + where)
                .params(params).query(Long.class).single();
        params.add(size);
        params.add((long) page * size);
        return new UserService.Page<>(jdbc.sql(SELECT + where + " ORDER BY i.created_at DESC, i.id LIMIT ? OFFSET ?")
                .params(params).query(this::map).list(), total);
    }

    Optional<InvitationView> latestFor(UUID userId) {
        return jdbc.sql(SELECT + " WHERE i.user_account_id = ? ORDER BY i.created_at DESC LIMIT 1").param(userId).query(this::map).optional();
    }

    /** Creates a pending invitation for an INVITED user (caller has checked permissions). */
    @Transactional
    public Issued issue(UUID userId) {
        record U(String email, String given, String status) {}
        U u = jdbc.sql("SELECT email, given_name, status FROM user_account WHERE id = ?").param(userId)
                .query((rs, n) -> new U(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        if (!"INVITED".equals(u.status())) {
            throw ApiException.conflict("already_signed_up", "This user already has a login; no invitation is needed.");
        }
        expireStale(userId);
        String token = newToken();
        UUID id = Ids.newId();
        Instant expires = Instant.now().plus(props.invitationTtl());
        try {
            jdbc.sql("""
                    INSERT INTO user_invitation (id, tenant_id, user_account_id, email, token_hash, expires_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), userId, u.email(), hash(token), Timestamp.from(expires),
                            CurrentUser.require().userId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("invitation_pending", "This user already has an open invitation. Re-send or revoke it instead.");
        }
        Map<String, String> details = Map.of("invitationId", id.toString(), "userId", userId.toString(), "expiresAt", expires.toString());
        audit.recordSuccess("user.invitation.sent", "user_invitation", id.toString(), details);
        outbox.append("user_invitation", id.toString(), "user.invitation.sent", details);
        return new Issued(id, u.email(), u.given(), token, expires);
    }

    /** Send for a user: re-sends (new token, new expiry) an open invitation, or issues a new one. */
    @Transactional
    public Issued resend(UUID userId, UserService users) {
        UserService.Row row = users.requireManageable(userId);
        if (row.summary().status() != UserService.AccountStatus.INVITED) {
            throw ApiException.conflict("already_signed_up", "This user already has a login; no invitation is needed.");
        }
        expireStale(userId);
        Optional<UUID> open = jdbc.sql("SELECT id FROM user_invitation WHERE user_account_id = ? AND status = 'PENDING' FOR UPDATE")
                .param(userId).query(UUID.class).optional();
        if (open.isEmpty()) return issue(userId);
        String token = newToken();
        Instant expires = Instant.now().plus(props.invitationTtl());
        jdbc.sql("""
                UPDATE user_invitation SET token_hash = ?, expires_at = ?, send_count = send_count + 1, last_sent_at = now(),
                       delivery_status = 'QUEUED', version = version + 1 WHERE id = ?""")
                .params(hash(token), Timestamp.from(expires), open.get()).update();
        Map<String, String> details = Map.of("invitationId", open.get().toString(), "userId", userId.toString(), "expiresAt", expires.toString());
        audit.recordSuccess("user.invitation.resent", "user_invitation", open.get().toString(), details);
        outbox.append("user_invitation", open.get().toString(), "user.invitation.resent", details);
        return new Issued(open.get(), row.summary().email(), row.summary().givenName(), token, expires);
    }

    @Transactional
    public void revoke(UUID invitationId, UserService users) {
        UUID userId = jdbc.sql("SELECT user_account_id FROM user_invitation WHERE id = ?").param(invitationId).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("Invitation"));
        users.requireManageable(userId);
        int n = jdbc.sql("UPDATE user_invitation SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'PENDING'")
                .params(CurrentUser.require().userId(), invitationId).update();
        if (n == 0) throw ApiException.conflict("invitation_not_open", "Only an open invitation can be revoked.");
        Map<String, String> details = Map.of("invitationId", invitationId.toString(), "userId", userId.toString());
        audit.recordSuccess("user.invitation.revoked", "user_invitation", invitationId.toString(), details);
        outbox.append("user_invitation", invitationId.toString(), "user.invitation.revoked", details);
    }

    /** Revokes any open invitation of the user (email change, deactivation). */
    void revokeOpenFor(UUID userId, String reason) {
        List<UUID> ids = jdbc.sql("UPDATE user_invitation SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, version = version + 1 "
                        + "WHERE user_account_id = ? AND status = 'PENDING' RETURNING id")
                .params(CurrentUser.require().userId(), userId).query(UUID.class).list();
        for (UUID id : ids) {
            audit.recordSuccess("user.invitation.revoked", "user_invitation", id.toString(),
                    Map.of("invitationId", id.toString(), "userId", userId.toString(), "reason", reason));
        }
    }

    /** Sends the email for a committed invitation and records the delivery outcome (own transaction). */
    public void deliver(Issued issued) {
        String org = jdbc.sql("SELECT name FROM tenant WHERE id = ?").param(TenantContext.requireTenantId()).query(String.class).single();
        String link = props.appBaseUrl().replaceAll("/+$", "") + "/invitation#token=" + issued.token();
        String until = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(ZoneId.of("Asia/Kolkata")).format(issued.expiresAt());
        String body = """
                Hello %s,

                You have been invited to join %s on Ivorovi, the assessment platform.

                Open this link to set your password and activate your account:
                %s

                The link can be used once and expires on %s.
                If you were not expecting this invitation, you can ignore this email.
                """.formatted(issued.givenName(), org, link, until);
        String status;
        try {
            mail.send(new EmailMessage(issued.email(), "Your invitation to " + org + " on Ivorovi", body));
            status = "SENT";
        } catch (RuntimeException e) {
            log.warn("Invitation email {} could not be sent: {}", issued.invitationId(), e.getClass().getSimpleName());
            status = "FAILED";
        }
        jdbc.sql("UPDATE user_invitation SET delivery_status = ? WHERE id = ?").params(status, issued.invitationId()).update();
        if ("FAILED".equals(status)) {
            audit.record("user.invitation.delivery_failed", "user_invitation", issued.invitationId().toString(),
                    com.ivorovi.platform.audit.AuditOutcome.FAILURE, Map.of("invitationId", issued.invitationId().toString()));
        }
    }

    // ---------------------------------------------------------------------------------------- public acceptance

    /** Resolves a token for the acceptance page. Unknown, used, revoked and expired tokens look the same. */
    public Lookup lookup(String token) {
        UUID tenant = tenantOf(token);
        return TenantContext.callInTenant(tenant, () -> {
            Open inv = open(token, false);
            boolean existing;
            try {
                existing = idp.findByEmail(inv.email()).map(u -> tenant.equals(u.tenantId())).orElse(false);
            } catch (IdentityProvisioner.UnavailableException e) {
                existing = false;   // the page still works; acceptance re-checks
            }
            String org = jdbc.sql("SELECT name FROM tenant WHERE id = ?").param(tenant).query(String.class).single();
            return new Lookup(org, inv.email(), inv.givenName(), inv.expiresAt(), existing);
        });
    }

    /**
     * Accepts an invitation: creates the identity-provider login with the chosen password (or links an existing
     * login of the same organization), activates the account and consumes the token. Single use is enforced by
     * locking the invitation row and changing its status in the same transaction.
     */
    public void accept(String token, char[] password) {
        UUID tenant = tenantOf(token);
        try {
            TenantContext.runInTenant(tenant, () -> tx.executeWithoutResult(s -> acceptInTenant(tenant, token, password)));
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
        }
    }

    private void acceptInTenant(UUID tenant, String token, char[] password) {
        Open inv = open(token, true);
        Optional<IdentityProvisioner.IdpUser> existing;
        try {
            existing = idp.findByEmail(inv.email());
        } catch (IdentityProvisioner.UnavailableException e) {
            throw unavailable();
        }
        String subject;
        if (existing.isPresent()) {
            if (!tenant.equals(existing.get().tenantId())) {
                throw new ApiException(HttpStatus.CONFLICT, "email_registered_elsewhere",
                        "This email address already has an Ivorovi login for another organization. Ask your administrator to invite a different address.");
            }
            boolean linked = jdbc.sql("SELECT count(*) FROM user_account WHERE external_subject = ?").param(existing.get().id())
                    .query(Long.class).single() > 0;
            if (linked) throw ApiException.conflict("login_already_linked", "This login already belongs to another account.");
            subject = existing.get().id();
        } else {
            if (password == null || password.length < 12) {
                throw ApiException.badRequest("weak_password", "Choose a password with at least 12 characters.");
            }
            try {
                subject = idp.create(new IdentityProvisioner.NewUser(inv.email(), inv.givenName(), inv.familyName(), tenant, password));
            } catch (IdentityProvisioner.RejectedException e) {
                throw ApiException.badRequest("password_rejected", e.getMessage());
            } catch (IdentityProvisioner.UnavailableException e) {
                throw unavailable();
            }
        }
        jdbc.sql("UPDATE user_invitation SET status = 'ACCEPTED', accepted_at = now(), version = version + 1 WHERE id = ?")
                .param(inv.id()).update();
        jdbc.sql("""
                UPDATE user_account SET external_subject = ?, status = 'ACTIVE', status_changed_at = now(),
                       updated_at = now(), version = version + 1 WHERE id = ?""").params(subject, inv.userId()).update();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("invitationId", inv.id().toString());
        details.put("userId", inv.userId().toString());
        details.put("login", existing.isPresent() ? "linked_existing" : "created");
        audit.recordSuccess("user.invitation.accepted", "user_invitation", inv.id().toString(), details);
        outbox.append("user_account", inv.userId().toString(), "user.activated", details);
        principals.evictSubject(tenant, subject);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private record Open(UUID id, UUID userId, String email, String givenName, String familyName, Instant expiresAt) {}

    private Open open(String token, boolean lock) {
        Optional<Open> inv = jdbc.sql("""
                SELECT i.id, i.user_account_id, i.email, u.given_name, u.family_name, i.expires_at
                FROM user_invitation i JOIN user_account u ON u.id = i.user_account_id
                WHERE i.token_hash = ? AND i.status = 'PENDING' AND i.expires_at > now() AND u.status = 'INVITED'
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param(hash(token))
                .query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant())).optional();
        return inv.orElseThrow(InvitationService::invalid);
    }

    private UUID tenantOf(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw invalid();
        UUID tenant = jdbc.sql("SELECT invitation_tenant(?)").param(hash(token)).query((rs, n) -> rs.getObject(1, UUID.class))
                .list().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (tenant == null) throw invalid();
        return tenant;
    }

    private void expireStale(UUID userId) {
        // an expired invitation can never be accepted; it is closed when a new one is issued
        jdbc.sql("UPDATE user_invitation SET status = 'EXPIRED', version = version + 1 WHERE user_account_id = ? AND status = 'PENDING' AND expires_at <= now()")
                .param(userId).update();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.NOT_FOUND, "invitation_invalid",
                "This invitation link is not valid. It may have expired, been replaced by a newer email, or already been used.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "identity_provider_unavailable",
                "The sign-in service is temporarily unavailable. Try again in a few minutes.");
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String STATE_SQL = "CASE WHEN i.status = 'PENDING' AND i.expires_at <= now() THEN 'EXPIRED' ELSE i.status END";
    private static final String SELECT =
            "SELECT i.id, i.user_account_id, i.email, u.display_name, " + STATE_SQL + " AS state, i.created_at, i.expires_at,"
            + " i.last_sent_at, i.send_count, i.delivery_status, i.accepted_at, i.revoked_at,"
            + " (SELECT b.display_name FROM user_account b WHERE b.id = i.created_by) AS invited_by, i.version"
            + " FROM user_invitation i JOIN user_account u ON u.id = i.user_account_id";

    private InvitationView map(ResultSet rs, int n) throws SQLException {
        return new InvitationView(rs.getObject("id", UUID.class), rs.getObject("user_account_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), State.valueOf(rs.getString("state")), UserService.instant(rs, "created_at"),
                UserService.instant(rs, "expires_at"), UserService.instant(rs, "last_sent_at"), rs.getInt("send_count"),
                rs.getString("delivery_status"), UserService.instant(rs, "accepted_at"), UserService.instant(rs, "revoked_at"),
                rs.getString("invited_by"), rs.getLong("version"));
    }
}
