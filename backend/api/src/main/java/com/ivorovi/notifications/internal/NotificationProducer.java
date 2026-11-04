package com.ivorovi.notifications.internal;

import com.ivorovi.notifications.NotificationService;
import com.ivorovi.notifications.NotificationService.Message;
import com.ivorovi.notifications.NotificationService.Severity;
import com.ivorovi.platform.outbox.IntegrationEvent;
import com.ivorovi.platform.tenancy.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns domain events into in-app notifications for the people concerned: invitations (accepted, email not delivered)
 * to the inviter; account status changes to the organization's user administrators; role grants and revocations to the
 * user; role permission changes to the role's holders; student lifecycle changes to the student's own account; import
 * results to the uploader and confirmer; integration and webhook failures to integration managers.
 * <p>
 * The actor of an event is never notified about their own action, except for results of background work they started
 * (import validation and completion). Messages carry identifiers and labels only — no
 * personal data beyond names already visible to the recipient. Consumption is at-least-once; the
 * (recipient, source event) uniqueness makes redelivery harmless. A failure here never affects the operation that
 * raised the event (it already committed).
 */
@Component
public class NotificationProducer {

    static final String QUEUE = "ivorovi.notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationProducer.class);
    private static final int MAX_BROADCAST = 50;

    /** Event types this consumer listens to (topic patterns). */
    static final List<String> KEYS = List.of("user.activated", "user.suspended", "user.deactivated", "user.reactivated", "user.invitation.delivery_failed",
            "role.assigned", "role.revoked", "role.updated", "role.deactivated", "student.status_changed", "student.enrolment.*", "student.import.finished",
            "student.import.validated", "integration.failing", "webhook.endpoint.disabled", "api_client.suspended", "service_identity.envelope_proposed",
            "service_identity.custodian_missing", "service_identity.suspended");

    @Configuration(proxyBeanMethods = false)
    static class Messaging {
        @Bean
        Declarables notificationQueue(TopicExchange ivoroviEventsExchange) {
            Queue q = new Queue(QUEUE, true);
            List<Object> all = new java.util.ArrayList<>(List.of(q));
            for (String k : KEYS) all.add(BindingBuilder.bind(q).to(ivoroviEventsExchange).with(k));
            return new Declarables(all.stream().map(o -> (org.springframework.amqp.core.Declarable) o).toList());
        }
    }

    private final NotificationService notifications;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    NotificationProducer(NotificationService notifications, JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE, autoStartup = "${ivorovi.notifications.consumer-enabled:true}")
    void onEvent(org.springframework.amqp.core.Message message) {
        try {
            handle(IntegrationEvent.from(message));
        } catch (RuntimeException e) {
            // never requeue forever: a notification is not worth blocking the queue
            log.warn("Notification for event {} not created: {}", message.getMessageProperties().getMessageId(), e.getClass().getSimpleName());
        }
    }

    /** Creates the notifications for one event; returns how many were created. */
    public int handle(IntegrationEvent e) {
        if (e.tenantId() == null || e.id() == null) return 0;
        Map<String, Object> d = json.readValue(e.payload() == null ? "{}" : e.payload(), new TypeReference<Map<String, Object>>() {});
        Integer n = TenantContext.callInTenant(e.tenantId(), () -> tx.execute(s -> route(e, d)));
        return n == null ? 0 : n;
    }

    private int route(IntegrationEvent e, Map<String, Object> d) {
        String type = e.eventType();
        String id = e.aggregateId();
        return switch (type) {
            case "user.activated" -> {
                Object inv = d.get("invitationId");
                if (inv == null) yield 0;
                Optional<UUID> inviter = jdbc.sql("SELECT created_by FROM user_invitation WHERE id = ?").param(UUID.fromString(inv.toString()))
                        .query(UUID.class).optional();
                String name = userName(UUID.fromString(id));
                yield send(e, inviter.stream().toList(), new Message("invitations", Severity.SUCCESS, "Invitation accepted",
                        name + " accepted the invitation and can now sign in.", "/people/users/" + id, e.id(), type, "user", id));
            }
            case "user.invitation.delivery_failed" -> {
                Optional<UUID> inviter = jdbc.sql("SELECT created_by FROM user_invitation WHERE id = ?").param(UUID.fromString(id)).query(UUID.class).optional();
                UUID user = jdbc.sql("SELECT user_account_id FROM user_invitation WHERE id = ?").param(UUID.fromString(id)).query(UUID.class).single();
                yield send(e, inviter.stream().toList(), new Message("invitations", Severity.WARNING, "Invitation email not delivered",
                        "The invitation email to " + userName(user) + " could not be sent. Check the address and send it again.", "/people/invitations",
                        e.id(), type, "user_invitation", id));
            }
            case "user.suspended", "user.deactivated", "user.reactivated" -> {
                String verb = type.substring("user.".length());
                yield send(e, holders("user.manage"), new Message("accounts", type.equals("user.reactivated") ? Severity.INFO : Severity.WARNING,
                        "Account " + verb, userName(UUID.fromString(id)) + "'s account was " + verb + ".", "/people/users/" + id, e.id(), type, "user", id));
            }
            case "role.assigned", "role.revoked" -> {
                UUID user = UUID.fromString(String.valueOf(d.get("userId")));
                String role = roleName(String.valueOf(d.get("role")));
                boolean granted = type.equals("role.assigned");
                yield send(e, List.of(user), new Message("access", granted ? Severity.INFO : Severity.WARNING, granted ? "Role assigned" : "Role removed",
                        granted ? "You now have the role " + role + scope(d) + "." : "The role " + role + scope(d) + " was removed from your account.",
                        "/me", e.id(), type, "role_assignment", id));
            }
            case "role.updated", "role.deactivated" -> {
                UUID role = UUID.fromString(id);
                String name = jdbc.sql("SELECT name FROM role WHERE id = ?").param(role).query(String.class).optional().orElse("A role");
                if (type.equals("role.updated") && d.get("permissionsAdded") == null && d.get("permissionsRemoved") == null && d.get("permissions") == null) yield 0;
                yield send(e, roleHolders(role), new Message("access", Severity.INFO, type.equals("role.updated") ? "Role permissions changed" : "Role deactivated",
                        type.equals("role.updated") ? "The permissions of your role " + name + " changed. Your access may be different."
                                : "Your role " + name + " was deactivated; it no longer grants access.", "/me", e.id(), type, "role", id));
            }
            case "student.status_changed" -> send(e, studentAccount(UUID.fromString(id)), new Message("students", Severity.INFO, "Your student status changed",
                    "Your status is now " + label(String.valueOf(d.get("to"))) + " (effective " + d.get("effectiveOn") + ").", null, e.id(), type, "student", id));
            case "student.import.validated" -> send(e, importPeople(UUID.fromString(id), false), new Message("imports", Severity.INFO, "Import ready to review",
                    "Checking of " + importFile(UUID.fromString(id)) + " finished (" + d.get("rows") + " rows). Review the results and confirm the import.",
                    "/students/imports/" + id, e.id(), type, "student_import", id));
            case "student.import.finished" -> {
                String status = String.valueOf(d.get("status"));
                Severity sev = switch (status) {
                    case "COMPLETED" -> Severity.SUCCESS;
                    case "PARTIALLY_COMPLETED" -> Severity.WARNING;
                    case "CANCELLED" -> Severity.INFO;
                    default -> Severity.ERROR;
                };
                String title = switch (status) {
                    case "COMPLETED" -> "Import completed";
                    case "PARTIALLY_COMPLETED" -> "Import partially completed";
                    case "CANCELLED" -> "Import cancelled";
                    default -> "Import failed";
                };
                yield send(e, importPeople(UUID.fromString(id), true), new Message("imports", sev, title,
                        importFile(UUID.fromString(id)) + ": " + title.toLowerCase().replace("import ", "") + ". Open the result for details.",
                        "/students/imports/" + id + "/result", e.id(), type, "student_import", id));
            }
            case "integration.failing" -> send(e, holders("integration.manage"), new Message("integrations", Severity.ERROR, "Integration failing",
                    "The integration " + d.get("name") + " failed " + d.get("failures") + " times in a row: " + d.get("error"), "/integrations/" + id, e.id(), type,
                    "integration", id));
            case "webhook.endpoint.disabled" -> send(e, holders("integration.manage"), new Message("integrations", Severity.ERROR, "Webhook endpoint disabled",
                    "Deliveries to " + d.get("name") + " failed " + d.get("failures") + " times in a row, so the endpoint was disabled. Fix it and re-enable it.",
                    "/integrations/webhooks/" + id, e.id(), type, "webhook_endpoint", id));
            case "api_client.suspended" -> send(e, holders("integration.manage"), new Message("integrations", Severity.WARNING, "API client suspended",
                    "The API client " + d.get("name") + " was suspended because its owner is no longer active. Reassign it to another owner or revoke it.",
                    "/integrations/api-clients/" + id, e.id(), type, "api_client", id));
            case "service_identity.envelope_proposed" -> send(e, holders("integration.manage"), new Message("integrations", Severity.INFO,
                    "Service identity needs approval", "A scope envelope for " + d.get("name") + " was proposed and needs approval by another integration manager.",
                    "/integrations/service-identities/" + id, e.id(), type, "service_identity", id));
            case "service_identity.custodian_missing" -> send(e, holders("integration.manage"), new Message("integrations", Severity.WARNING,
                    "Service identity needs a custodian", d.get("name") + " has no active custodian. Name one within 30 days or it will be suspended.",
                    "/integrations/service-identities/" + id, e.id(), type, "service_identity", id));
            case "service_identity.suspended" -> send(e, holders("integration.manage"), new Message("integrations", Severity.ERROR, "Service identity suspended",
                    d.get("name") + " was suspended (" + label(String.valueOf(d.get("reason"))) + "). Its API clients stop working until it is resumed.",
                    "/integrations/service-identities/" + id, e.id(), type, "service_identity", id));
            default -> type.startsWith("student.enrolment.")
                    ? send(e, studentAccount(UUID.fromString(id)), new Message("students", Severity.INFO, "Your enrolment changed",
                            label(type.substring("student.enrolment.".length())) + ": batch " + d.get("batch") + ", term " + d.get("termNumber") + ".", null, e.id(), type,
                            "student", id))
                    : 0;
        };
    }

    /** Results of background work (imports) reach the person who started it; other events never inform their actor. */
    private static final Set<String> ASYNC_RESULTS = Set.of("student.import.validated", "student.import.finished");

    private int send(IntegrationEvent e, List<UUID> recipients, Message m) {
        Set<UUID> to = new LinkedHashSet<>(recipients);
        to.remove(null);
        if (e.actorId() != null && !ASYNC_RESULTS.contains(e.eventType())) to.remove(e.actorId());
        int n = 0;
        for (UUID r : to) {
            boolean active = jdbc.sql("SELECT status FROM user_account WHERE id = ?").param(r).query(String.class).optional()
                    .map(s -> s.equals("ACTIVE")).orElse(false);
            if (active && notifications.send(r, m) != null) n++;
        }
        return n;
    }

    /** Active users holding the permission organization-wide (capped). */
    private List<UUID> holders(String permission) {
        return jdbc.sql("""
                SELECT DISTINCT ra.user_account_id FROM role_assignment ra JOIN role r ON r.id = ra.role_id JOIN user_account u ON u.id = ra.user_account_id
                WHERE ra.revoked_at IS NULL AND ra.scope_type = 'TENANT' AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())
                  AND r.status = 'ACTIVE' AND r.archived_at IS NULL AND ? = ANY (r.permissions) AND u.status = 'ACTIVE'
                ORDER BY 1 LIMIT ?""").params(permission, MAX_BROADCAST).query(UUID.class).list();
    }

    private List<UUID> roleHolders(UUID role) {
        return jdbc.sql("""
                SELECT DISTINCT ra.user_account_id FROM role_assignment ra WHERE ra.role_id = ? AND ra.revoked_at IS NULL
                  AND (ra.valid_to IS NULL OR ra.valid_to > now()) ORDER BY 1 LIMIT ?""").params(role, MAX_BROADCAST * 4).query(UUID.class).list();
    }

    private List<UUID> studentAccount(UUID student) {
        return jdbc.sql("SELECT user_account_id FROM student WHERE id = ? AND user_account_id IS NOT NULL").param(student).query(UUID.class).list();
    }

    private List<UUID> importPeople(UUID job, boolean withConfirmer) {
        return jdbc.sql("SELECT created_by, confirmed_by FROM student_import_job WHERE id = ?").param(job)
                .query((rs, n) -> {
                    List<UUID> l = new java.util.ArrayList<>();
                    l.add(rs.getObject(1, UUID.class));
                    if (withConfirmer) l.add(rs.getObject(2, UUID.class));
                    return l;
                }).optional().orElse(List.of());
    }

    private String importFile(UUID job) {
        return jdbc.sql("SELECT file_name FROM student_import_job WHERE id = ?").param(job).query(String.class).optional().orElse("The import");
    }

    private String userName(UUID user) {
        return jdbc.sql("SELECT display_name FROM user_account WHERE id = ?").param(user).query(String.class).optional().orElse("A user");
    }

    private String roleName(String code) {
        return jdbc.sql("SELECT name FROM role WHERE code = ? ORDER BY tenant_id NULLS LAST LIMIT 1").param(code).query(String.class).optional().orElse(code);
    }

    private String scope(Map<String, Object> d) {
        Object s = d.get("scope");
        if (s == null || !s.toString().startsWith("ORG_UNIT:")) return "";
        UUID unit = UUID.fromString(s.toString().substring("ORG_UNIT:".length()));
        return jdbc.sql("SELECT name FROM org_unit WHERE id = ?").param(unit).query(String.class).optional().map(n -> " in " + n).orElse("");
    }

    private static String label(String code) {
        String s = code.replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
