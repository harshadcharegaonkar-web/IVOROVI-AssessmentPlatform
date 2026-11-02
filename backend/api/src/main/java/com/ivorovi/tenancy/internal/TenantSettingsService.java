package com.ivorovi.tenancy.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.tenancy.OrgUnitType;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Organization settings: display labels for the stable unit types, and the organization's time zone. */
@Service
public class TenantSettingsService {

    @io.swagger.v3.oas.annotations.media.Schema(name = "TenantSettings")
    public record Settings(Map<OrgUnitType, Label> labels, String timeZone, long version) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "UnitTypeLabel")
    public record Label(String singular, String plural) {}

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditLog audit;
    private final com.ivorovi.platform.security.Authz authz;

    TenantSettingsService(JdbcClient jdbc, JsonMapper json, AuditLog audit, com.ivorovi.platform.security.Authz authz) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        record Row(String settings, long version) {}
        Row row = jdbc.sql("SELECT settings::text, version FROM tenant_settings WHERE tenant_id = ?")
                .param(TenantContext.requireTenantId())
                .query((rs, n) -> new Row(rs.getString(1), rs.getLong(2))).optional().orElse(new Row("{}", -1));
        Map<String, Object> raw = json.readValue(row.settings(), MAP);
        Map<OrgUnitType, Label> labels = new EnumMap<>(OrgUnitType.class);
        Object stored = raw.get("labels");
        for (OrgUnitType t : OrgUnitType.values()) {
            Label label = new Label(t.defaultLabel(), t.defaultPluralLabel());
            if (stored instanceof Map<?, ?> m && m.get(t.name()) instanceof Map<?, ?> l) {
                label = new Label(String.valueOf(l.get("singular")), String.valueOf(l.get("plural")));
            }
            labels.put(t, label);
        }
        String zone = raw.get("timeZone") instanceof String z ? z : "Asia/Kolkata";
        return new Settings(labels, zone, row.version());
    }

    @Transactional
    public Settings update(Map<OrgUnitType, Label> labels, String timeZone, long expectedVersion) {
        if (!authz.has(Permission.TENANT_SETTINGS_MANAGE.key())) throw new AccessDeniedException("Missing permission");
        Settings current = get();
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("concurrent_modification", "Settings were changed by someone else. Reload and try again.");
        }
        Map<String, Object> labelsJson = new LinkedHashMap<>();
        for (OrgUnitType t : OrgUnitType.values()) {
            Label l = labels == null ? null : labels.get(t);
            if (l == null) l = current.labels().get(t);
            String singular = clean(l.singular()), plural = clean(l.plural());
            labelsJson.put(t.name(), Map.of("singular", singular, "plural", plural));
        }
        String zone = timeZone == null ? current.timeZone() : timeZone.trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_time_zone", "Unknown time zone '" + zone + "'.");
        }
        String settings = json.writeValueAsString(Map.of("labels", labelsJson, "timeZone", zone));
        jdbc.sql("""
                INSERT INTO tenant_settings (tenant_id, settings, updated_by) VALUES (?, CAST(? AS jsonb), ?)
                ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings, updated_at = now(),
                    updated_by = EXCLUDED.updated_by, version = tenant_settings.version + 1""")
                .params(TenantContext.requireTenantId(), settings, CurrentUser.require().userId()).update();
        Map<String, String> details = new LinkedHashMap<>();
        labelsJson.forEach((k, v) -> details.put("label." + k, String.valueOf(((Map<?, ?>) v).get("singular"))));
        details.put("timeZone", zone);
        audit.recordSuccess("tenant.settings.updated", "tenant_settings", TenantContext.requireTenantId().toString(), details);
        return get();
    }

    private static String clean(String s) {
        if (s == null || s.isBlank() || s.trim().length() > 40 || !s.trim().matches("[\\p{L}\\p{N} &'()./-]+")) {
            throw ApiException.badRequest("invalid_label", "Labels are 1-40 characters (letters, digits, spaces and & ' ( ) . / -).");
        }
        return s.trim();
    }
}
