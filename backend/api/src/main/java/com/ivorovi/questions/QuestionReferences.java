package com.ivorovi.questions;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module API for records that must keep a question version alive (assessments from 2.2, exam papers, results). A
 * referenced version can never be deleted; references are permanent; only approved, superseded or retired versions can be
 * referenced. Retiring or superseding a question never changes a referenced version.
 */
@Component
public class QuestionReferences {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    QuestionReferences(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public void reference(UUID versionId, String referrerType, String referrerId) {
        try {
            int n = jdbc.sql("""
                    INSERT INTO question_version_reference (id, tenant_id, version_id, referrer_type, referrer_id) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, version_id, referrer_type, referrer_id) DO NOTHING""")
                    .params(Ids.newId(), TenantContext.requireTenantId(), versionId, referrerType, referrerId).update();
            if (n > 0) audit.recordSuccess("question.version_referenced", "question_version", versionId.toString(), Map.of("by", referrerType + ":" + referrerId));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("version_not_referenceable", "Only approved question versions can be referenced.");
        }
    }

    /**
     * Bulk form for a publication (e.g. every fixed and pooled question of an assessment version): one statement, one
     * audit event with the count. Same rules: only approved, superseded or retired versions; references are permanent.
     */
    @Transactional
    public int referenceAll(java.util.Collection<UUID> versionIds, String referrerType, String referrerId) {
        if (versionIds.isEmpty()) return 0;
        int n;
        try {
            n = jdbc.sql("""
                    INSERT INTO question_version_reference (id, tenant_id, version_id, referrer_type, referrer_id)
                    SELECT gen_random_uuid(), ?, v, ?, ? FROM unnest(?) AS v
                    ON CONFLICT (tenant_id, version_id, referrer_type, referrer_id) DO NOTHING""")
                    .params(TenantContext.requireTenantId(), referrerType, referrerId, versionIds.toArray(UUID[]::new)).update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("version_not_referenceable", "Only approved question versions can be referenced.");
        }
        audit.recordSuccess("question.versions_referenced", referrerType, referrerId, Map.of("count", String.valueOf(n)));
        return n;
    }

    public long referenceCount(UUID versionId) {
        return jdbc.sql("SELECT count(*) FROM question_version_reference WHERE version_id = ?").param(versionId).query(Long.class).single();
    }
}
