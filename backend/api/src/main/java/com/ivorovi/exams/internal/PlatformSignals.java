package com.ivorovi.exams.internal;

import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.process.ProcessHeartbeat;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signals a delivery instance reports to the platform (platform-only table). The health detector in the administrative
 * process correlates them into operator incidents; nothing here blocks a candidate. Best effort: a failed write is logged.
 */
@Component
public class PlatformSignals {

    public static final String CHECK_IN_ANOMALY = "CHECK_IN_ANOMALY";
    public static final String ADDRESS_THROTTLED = "ADDRESS_THROTTLED";

    private static final Logger log = LoggerFactory.getLogger(PlatformSignals.class);
    private final JdbcClient jdbc;
    private final ProcessHeartbeat process;
    private final JsonMapper json;

    PlatformSignals(JdbcClient jdbc, ProcessHeartbeat process, JsonMapper json) {
        this.jdbc = jdbc;
        this.process = process;
        this.json = json;
    }

    public void raise(String kind, UUID tenant, UUID portal, Map<String, ?> detail) {
        try {
            String d = json.writeValueAsString(detail);
            TenantContext.callAsPlatform(() -> jdbc.sql("""
                    INSERT INTO platform_signal (id, kind, tenant_id, portal_id, instance_id, detail) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))""")
                    .params(Ids.newId(), kind, tenant, portal, process.instanceId(), d).update());
            log.warn("platform.signal kind={} tenant={} portal={} detail={}", kind, tenant, portal, d);
        } catch (RuntimeException e) {
            log.error("Could not record platform signal {}: {}", kind, e.toString());
        }
    }
}
