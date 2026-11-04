package com.ivorovi.integrations.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The subscribable event catalogue and the payload whitelist. Webhook payloads are built only from whitelisted fields
 * of the domain event: free text (reasons), names of people, email addresses, dates of birth and every other personal
 * value are never sent. Student, staff and admission identifiers are sent only when the endpoint opted in.
 */
public final class WebhookEvents {

    private WebhookEvents() {}

    @Schema(name = "WebhookEventType")
    public record Type(String key, String label, String description, boolean mayIncludeIdentifiers) {}

    static final String PING = "webhook.ping";

    static final List<Type> CATALOGUE = List.of(
            new Type("student.created", "Student created", "A student record was created (manually or by import).", true),
            new Type("student.updated", "Student updated", "A student's profile changed (which fields is not sent).", true),
            new Type("student.status_changed", "Student status changed", "Lifecycle status change with from, to and effective date.", true),
            new Type("student.enrolment.*", "Enrolment changes", "Initial enrolment, progression, promotion, transfer and other enrolment changes.", true),
            new Type("student.admission.*", "Admission records", "Admission recorded, updated, confirmed or cancelled.", true),
            new Type("student.import.finished", "Student import finished", "A bulk import completed, partially completed, failed or was cancelled.", false),
            new Type("staff.*", "Staff profiles", "Staff profile created, updated, linked to or unlinked from a user account.", true),
            new Type("user.*", "User accounts", "User account created, activated, suspended, reactivated or deactivated.", false),
            new Type("role.assigned", "Role assigned", "A role was assigned to a user (user id, role code and scope).", false),
            new Type("role.revoked", "Role revoked", "A role assignment was revoked (user id, role code and scope).", false),
            new Type("org_unit.*", "Organization units", "Universities, colleges, campuses and departments created, changed or moved.", false),
            new Type("academics.*", "Academic structure", "Programs, courses, subjects, calendar, batches, offerings and faculty assignments.", false));

    static final Set<String> KEYS = Set.copyOf(CATALOGUE.stream().map(Type::key).toList());

    /** Routing keys of the fan-out queue. */
    static final List<String> BINDINGS = List.of("student.created", "student.updated", "student.status_changed", "student.enrolment.#", "student.admission.#",
            "student.import.finished", "staff.#", "user.created", "user.activated", "user.suspended", "user.reactivated", "user.deactivated", "user.updated",
            "role.assigned", "role.revoked", "org_unit.#", "academics.#");

    /** Fields of people-related events that are always safe to send. */
    private static final Set<String> SAFE = Set.of("status", "from", "to", "effectiveOn", "type", "termNumber", "role", "scope", "jobId", "phase", "userId",
            "invitationId", "login", "changeType");
    /** Additionally safe for organizational and academic structure (no personal data). */
    private static final Set<String> STRUCTURE = Set.of("code", "name", "owner", "unitType", "year", "sequence", "batch", "course", "program", "subject",
            "department", "parentId", "affectedAssignments");
    /** Sent only to endpoints that opted in to identifiers. */
    private static final Set<String> IDENTIFIERS = Set.of("studentNo", "staffNo", "admissionNo", "batch");

    static boolean matches(String subscription, String eventType) {
        return subscription.endsWith(".*") ? eventType.startsWith(subscription.substring(0, subscription.length() - 1)) : subscription.equals(eventType);
    }

    static Map<String, Object> data(String eventType, String resourceType, String resourceId, Map<String, Object> details, boolean identifiers) {
        boolean structure = eventType.startsWith("academics.") || eventType.startsWith("org_unit.");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("resourceType", resourceType);
        d.put("resourceId", resourceId);
        for (Map.Entry<String, Object> e : details.entrySet()) {
            String k = e.getKey();
            if (SAFE.contains(k) || (structure && STRUCTURE.contains(k)) || (identifiers && IDENTIFIERS.contains(k))) d.put(k, e.getValue());
        }
        return d;
    }
}
