package com.ivorovi.students.internal;

import com.ivorovi.identity.StudentAccounts;
import com.ivorovi.platform.web.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * On-demand sign-in accounts for students (never created automatically). Needs {@code student.account.manage}; the
 * account gets only the STUDENT role at the student's institution, goes through the normal invitation flow, and is
 * linked to exactly one student profile.
 */
@Service
public class StudentAccountLink {

    private static final Set<String> ELIGIBLE = Set.of("ADMITTED", "ENROLLED", "ACTIVE", "SUSPENDED", "GRADUATED", "ALUMNI");

    private final JdbcClient jdbc;
    private final StudentAccess access;
    private final StudentAccounts accounts;

    StudentAccountLink(JdbcClient jdbc, StudentAccess access, StudentAccounts accounts) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
    }

    @Transactional
    public UUID create(UUID studentId, String email) {
        StudentAccess.StudentRef s = access.lock(studentId);
        access.require(StudentAccess.ACCOUNT, s);
        if (!s.active()) throw StudentAccess.conflict("student_inactive", "This student profile is deactivated.");
        if (s.userAccountId() != null) throw StudentAccess.conflict("account_exists", "This student already has a sign-in account.");
        if (!ELIGIBLE.contains(s.status())) {
            throw StudentAccess.conflict("student_not_eligible", "Sign-in accounts are created for admitted, enrolled, active, suspended or graduated students.");
        }
        record N(String first, String last, String institutional) {}
        N n = jdbc.sql("SELECT first_name, last_name, institutional_email FROM student WHERE id = ?").param(studentId)
                .query((rs, i) -> new N(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        String target = StudentService.email("email", email == null || email.isBlank() ? n.institutional() : email);
        if (target == null) throw ApiException.badRequest("invalid_email", "Enter the email address for the sign-in account.");
        UUID userId;
        try {
            userId = accounts.createInvited(target, n.first(), n.last(), s.institutionId());
        } catch (StudentAccounts.EmailInUseException e) {
            throw StudentAccess.conflict("email_in_use", "An account with this email address already exists in your organization.");
        }
        jdbc.sql("UPDATE student SET user_account_id = ?, updated_at = now(), version = version + 1 WHERE id = ?").params(userId, studentId).update();
        access.changed("student.account.created", s, "Sign-in account created (student role only); invitation sent",
                Map.of("userId", userId.toString(), "role", "STUDENT"));
        return userId;
    }
}
