package com.ivorovi.identity.internal;

import com.ivorovi.identity.StudentAccounts;
import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class StudentAccountService implements StudentAccounts {

    /** System role STUDENT (V202611020901). */
    static final UUID STUDENT_ROLE = UUID.fromString("01920000-0000-7000-8000-00000000000e");

    private final JdbcClient jdbc;
    private final InvitationService invitations;
    private final AuditLog audit;
    private final Outbox outbox;

    StudentAccountService(JdbcClient jdbc, InvitationService invitations, AuditLog audit, Outbox outbox) {
        this.jdbc = jdbc;
        this.invitations = invitations;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createInvited(String email, String givenName, String familyName, UUID institutionId) {
        String normalised = UserService.normaliseEmail(email);
        UUID tenant = TenantContext.requireTenantId();
        UUID actor = CurrentUser.require().userId();
        UUID id = Ids.newId();
        String display = familyName == null || familyName.isBlank() ? givenName : givenName + " " + familyName;
        try {
            jdbc.sql("""
                    INSERT INTO user_account (id, tenant_id, external_subject, email, display_name, given_name, family_name, status, created_by, updated_by)
                    VALUES (?, ?, NULL, ?, ?, ?, ?, 'INVITED', ?, ?)""")
                    .params(id, tenant, normalised, display, givenName, familyName, actor, actor).update();
        } catch (DuplicateKeyException e) {
            throw new EmailInUseException();
        }
        jdbc.sql("INSERT INTO org_assignment (id, tenant_id, user_account_id, org_unit_id, is_primary, title, created_by) VALUES (?, ?, ?, ?, true, 'Student', ?)")
                .params(Ids.newId(), tenant, id, institutionId, actor).update();
        UUID assignment = Ids.newId();
        jdbc.sql("INSERT INTO role_assignment (id, tenant_id, user_account_id, role_id, scope_type, scope_id, created_by) VALUES (?, ?, ?, ?, 'ORG_UNIT', ?, ?)")
                .params(assignment, tenant, id, STUDENT_ROLE, institutionId, actor).update();
        Map<String, String> details = Map.of("userId", id.toString(), "role", "STUDENT", "orgUnitId", institutionId.toString(), "origin", "student_profile");
        audit.recordSuccess("user.created", "user_account", id.toString(), details);
        audit.recordSuccess("role.assigned", "role_assignment", assignment.toString(), details);
        outbox.append("user_account", id.toString(), "user.created", details);
        InvitationService.Issued issued = invitations.issue(id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invitations.deliver(issued);
            }
        });
        return id;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> find(UUID userId) {
        if (userId == null) return Optional.empty();
        return jdbc.sql("""
                SELECT u.id, u.email, u.status,
                       (SELECT CASE WHEN i.accepted_at IS NOT NULL THEN 'ACCEPTED' WHEN i.revoked_at IS NOT NULL THEN 'REVOKED'
                                    WHEN i.expires_at < now() THEN 'EXPIRED' ELSE 'PENDING' END
                        FROM user_invitation i WHERE i.user_account_id = u.id ORDER BY i.created_at DESC LIMIT 1)
                FROM user_account u WHERE u.id = ?""").param(userId)
                .query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
    }
}
