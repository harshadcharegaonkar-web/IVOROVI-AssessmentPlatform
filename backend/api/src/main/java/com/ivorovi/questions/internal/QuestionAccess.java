package com.ivorovi.questions.internal;

import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.web.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Permission checks for the question bank. Everything is scoped to the bank's organization unit (inherited down the
 * hierarchy); a bank the caller may not read behaves as if it did not exist (404).
 */
@Component
public class QuestionAccess {

    public static final String READ = "question.read";
    public static final String AUTHOR = "question.author";
    public static final String REVIEW = "question.review";
    public static final String APPROVE = "question.approve";
    public static final String KEY_READ = "question.key.read";
    public static final String MANAGE = "question.bank.manage";

    public record BankRef(UUID id, UUID orgUnitId, String status) {}

    private final JdbcClient jdbc;
    private final Authz authz;

    QuestionAccess(JdbcClient jdbc, Authz authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public UUID me() {
        return CurrentUser.require().userId();
    }

    public boolean anywhere(String permission) {
        return authz.hasAnywhere(permission);
    }

    public boolean can(String permission, UUID unit) {
        return authz.hasInOrgUnit(permission, unit);
    }

    /** The bank, provided the caller may at least read it. */
    public BankRef bank(UUID bankId) {
        BankRef b = jdbc.sql("SELECT id, org_unit_id, status FROM question_bank WHERE id = ?").param(bankId)
                .query((rs, n) -> new BankRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).optional()
                .orElseThrow(() -> ApiException.notFound("Question bank"));
        if (!can(READ, b.orgUnitId())) throw ApiException.notFound("Question bank");
        return b;
    }

    public BankRef bankOfQuestion(UUID questionId) {
        UUID bank = jdbc.sql("SELECT bank_id FROM question WHERE id = ?").param(questionId).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Question"));
        try {
            return bank(bank);
        } catch (ApiException e) {
            throw ApiException.notFound("Question");
        }
    }

    public void require(String permission, BankRef bank) {
        if (!can(permission, bank.orgUnitId())) throw new AccessDeniedException("Missing permission " + permission);
    }
}
