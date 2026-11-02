package com.ivorovi.tenancy.internal;

import com.ivorovi.platform.audit.AuditLog;
import com.ivorovi.platform.ids.Ids;
import com.ivorovi.platform.outbox.Outbox;
import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.ApiException;
import com.ivorovi.tenancy.OrgUnitLookup;
import com.ivorovi.tenancy.OrgUnitType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Organization hierarchy: creation, editing, moves, lifecycle and history. Every mutation runs in one transaction
 * that also writes history, the audit trail and an outbox event, so they commit or roll back together.
 */
@Service
public class OrgUnitService implements OrgUnitLookup {

    public enum Status { ACTIVE, INACTIVE }

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnit")
    public record OrgUnit(UUID id, UUID parentId, String parentName, OrgUnitType type, String code, String name,
                          String shortName, Status status, String path, Map<String, String> attributes,
                          Map<String, String> branding, Instant createdAt, Instant updatedAt, long version) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitAncestor")
    public record Ancestor(UUID id, OrgUnitType type, String code, String name) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitDependents")
    public record Dependents(long children, long activeChildren, long roleAssignments, long featureOverrides) {
        public boolean any() { return children + roleAssignments + featureOverrides > 0; }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitDetail")
    public record Detail(OrgUnit unit, List<Ancestor> ancestors, Map<OrgUnitType, Long> childCounts, Dependents dependents) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitTreeNode")
    public record TreeNode(UUID id, UUID parentId, OrgUnitType type, String code, String name, Status status, int childCount) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitHistoryEntry")
    public record HistoryEntry(UUID id, String changeType, Instant changedAt, UUID changedBy, String changedByName,
                               String reason, Map<String, Object> before, Map<String, Object> after) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "OrgUnitSummary")
    public record Summary(Map<OrgUnitType, Long> active, Map<OrgUnitType, Long> inactive, List<Distribution> institutions) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "InstitutionDistribution")
    public record Distribution(UUID id, OrgUnitType type, String name, long campuses, long departments) {}

    public record Query(OrgUnitType type, UUID parentId, Status status, String q) {}

    public record Page<T>(List<T> items, long total) {}

    public record CreateCommand(OrgUnitType type, UUID parentId, String code, String name, String shortName,
                                Map<String, String> attributes, Map<String, String> branding) {}

    public record UpdateCommand(long version, String code, String name, String shortName,
                                Map<String, String> attributes, Map<String, String> branding) {}

    static final Set<String> ATTRIBUTE_KEYS = Set.of("description", "address", "city", "state", "country", "postalCode",
            "website", "email", "phone", "affiliationNumber", "establishedYear");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$");
    private static final Map<String, String> SORTS = Map.of("name", "u.name", "code", "u.code", "type", "u.unit_type",
            "createdAt", "u.created_at", "status", "u.status");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final String READ = Permission.ORG_UNIT_READ.key();
    private static final String MANAGE = Permission.ORG_UNIT_MANAGE.key();

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Authz authz;
    private final AuditLog audit;
    private final Outbox outbox;
    private final OrgPathCache paths;

    OrgUnitService(JdbcClient jdbc, JsonMapper json, Authz authz, AuditLog audit, Outbox outbox, OrgPathCache paths) {
        this.jdbc = jdbc;
        this.json = json;
        this.authz = authz;
        this.audit = audit;
        this.outbox = outbox;
        this.paths = paths;
    }

    // ------------------------------------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<OrgUnit> list(Query q, int page, int size, String sort, boolean ascending) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (q.type() != null) { where.append(" AND u.unit_type = ?"); params.add(q.type().name()); }
        if (q.parentId() != null) { where.append(" AND u.parent_id = ?"); params.add(q.parentId()); }
        if (q.status() != null) { where.append(" AND u.status = ?"); params.add(q.status().name()); }
        if (q.q() != null && !q.q().isBlank()) {
            where.append(" AND (u.name ILIKE ? OR u.code ILIKE ? OR u.short_name ILIKE ?)");
            String like = "%" + q.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (!appendVisibility(where, params)) return new Page<>(List.of(), 0);
        long total = jdbc.sql("SELECT count(*) FROM org_unit u" + where).params(params).query(Long.class).single();
        String order = SORTS.getOrDefault(sort, "u.name") + (ascending ? " ASC" : " DESC") + ", u.id";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<OrgUnit> items = jdbc.sql(SELECT_UNIT + where + " ORDER BY " + order + " LIMIT ? OFFSET ?")
                .params(pageParams).query(this::mapUnit).list();
        return new Page<>(items, total);
    }

    @Transactional(readOnly = true)
    public List<TreeNode> tree() {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!appendVisibility(where, params)) return List.of();
        return jdbc.sql("""
                SELECT u.id, u.parent_id, u.unit_type, u.code, u.name, u.status,
                       (SELECT count(*) FROM org_unit c WHERE c.parent_id = u.id) AS child_count
                FROM org_unit u""" + where + " ORDER BY u.path")
                .params(params)
                .query((rs, n) -> new TreeNode(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                        OrgUnitType.valueOf(rs.getString("unit_type")), rs.getString("code"), rs.getString("name"),
                        Status.valueOf(rs.getString("status")), rs.getInt("child_count")))
                .list();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<OrgUnitType, Long> active = new TreeMap<>();
        Map<OrgUnitType, Long> inactive = new TreeMap<>();
        for (OrgUnitType t : OrgUnitType.values()) { active.put(t, 0L); inactive.put(t, 0L); }
        if (!appendVisibility(where, params)) return new Summary(active, inactive, List.of());
        jdbc.sql("SELECT u.unit_type, u.status, count(*) FROM org_unit u" + where + " GROUP BY 1, 2").params(params)
                .query(rs -> {
                    OrgUnitType t = OrgUnitType.valueOf(rs.getString(1));
                    ("ACTIVE".equals(rs.getString(2)) ? active : inactive).put(t, rs.getLong(3));
                });
        List<Distribution> dist = jdbc.sql("""
                SELECT u.id, u.unit_type, u.name,
                       count(d.id) FILTER (WHERE d.unit_type = 'CAMPUS' AND d.status = 'ACTIVE') AS campuses,
                       count(d.id) FILTER (WHERE d.unit_type = 'DEPARTMENT' AND d.status = 'ACTIVE') AS departments
                FROM org_unit u LEFT JOIN org_unit d ON d.path <@ u.path AND d.id <> u.id""" + where
                        + " AND u.unit_type IN ('UNIVERSITY', 'COLLEGE') AND u.status = 'ACTIVE'"
                        + " GROUP BY u.id, u.unit_type, u.name ORDER BY departments DESC, u.name LIMIT 12")
                .params(params)
                .query((rs, n) -> new Distribution(rs.getObject("id", UUID.class), OrgUnitType.valueOf(rs.getString("unit_type")),
                        rs.getString("name"), rs.getLong("campuses"), rs.getLong("departments")))
                .list();
        return new Summary(active, inactive, dist);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        authz.require(READ, id);
        OrgUnit unit = load(id);
        Map<OrgUnitType, Long> childCounts = new TreeMap<>();
        jdbc.sql("SELECT unit_type, count(*) FROM org_unit WHERE parent_id = ? GROUP BY 1").param(id)
                .query(rs -> { childCounts.put(OrgUnitType.valueOf(rs.getString(1)), rs.getLong(2)); });
        return new Detail(unit, ancestors(unit), childCounts, dependents(id));
    }

    @Transactional(readOnly = true)
    public Page<HistoryEntry> history(UUID id, int page, int size) {
        authz.require(READ, id);
        load(id);
        long total = jdbc.sql("SELECT count(*) FROM org_unit_history WHERE org_unit_id = ?").param(id).query(Long.class).single();
        List<HistoryEntry> items = jdbc.sql("""
                SELECT h.id, h.change_type, h.changed_at, h.changed_by, ua.display_name, h.reason,
                       h.before::text AS before, h.after::text AS after
                FROM org_unit_history h LEFT JOIN user_account ua ON ua.tenant_id = h.tenant_id AND ua.id = h.changed_by
                WHERE h.org_unit_id = ? ORDER BY h.changed_at DESC, h.id DESC LIMIT ? OFFSET ?""")
                .params(id, size, (long) page * size)
                .query((rs, n) -> new HistoryEntry(rs.getObject("id", UUID.class), rs.getString("change_type"),
                        rs.getTimestamp("changed_at").toInstant(), rs.getObject("changed_by", UUID.class), rs.getString(5),
                        rs.getString("reason"), readObject(rs.getString("before")), readObject(rs.getString("after"))))
                .list();
        return new Page<>(items, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrgUnitRef> find(UUID orgUnitId) {
        return findUnit(orgUnitId).map(u -> new OrgUnitRef(u.id(), u.parentId(), u.type(), u.code(), u.name(), u.status().name()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnitRef> ancestorsOrSelf(UUID orgUnitId) {
        return findUnit(orgUnitId).map(u -> {
            List<UUID> ids = OrgPaths.ids(u.path());
            Map<UUID, OrgUnitRef> byId = new LinkedHashMap<>();
            jdbc.sql("SELECT id, parent_id, unit_type, code, name, status FROM org_unit WHERE id = ANY(?)")
                    .param(ids.toArray(UUID[]::new))
                    .query(rs -> {
                        UUID uid = rs.getObject("id", UUID.class);
                        byId.put(uid, new OrgUnitRef(uid, rs.getObject("parent_id", UUID.class), OrgUnitType.valueOf(rs.getString("unit_type")),
                                rs.getString("code"), rs.getString("name"), rs.getString("status")));
                    });
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }).orElse(List.of());
    }

    // ---------------------------------------------------------------------------------------------- commands

    @Transactional
    public Detail create(CreateCommand c) {
        if (c.type() == null) throw ApiException.badRequest("type_required", "Unit type is required.");
        OrgUnit parent = null;
        if (c.parentId() == null) {
            if (!c.type().mayBeTopLevel()) {
                throw invalidHierarchy(c.type(), null);
            }
            authz.require(MANAGE, null);
        } else {
            authz.require(MANAGE, c.parentId());
            parent = findUnit(c.parentId()).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "invalid_parent", "The selected parent does not exist in your organization."));
            if (!c.type().allowedParents().contains(parent.type())) throw invalidHierarchy(c.type(), parent.type());
            if (parent.status() != Status.ACTIVE) {
                throw ApiException.conflict("parent_inactive", "Units cannot be added under an inactive parent.");
            }
        }
        String code = validCode(c.code());
        String name = validName(c.name());
        Map<String, String> attributes = validAttributes(c.attributes());
        Map<String, String> branding = validBranding(c.type(), c.branding());
        UUID id = Ids.newId();
        String path = OrgPaths.childPath(parent == null ? null : parent.path(), id);
        UUID actor = CurrentUser.require().userId();
        try {
            jdbc.sql("""
                    INSERT INTO org_unit (id, tenant_id, parent_id, unit_type, code, name, short_name, path, attributes, branding,
                                          created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS ltree), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)""")
                    .params(id, TenantContext.requireTenantId(), c.parentId(), c.type().name(), code, name,
                            blankToNull(c.shortName()), path, json.writeValueAsString(attributes),
                            json.writeValueAsString(branding), actor, actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw duplicateCode(code);
        }
        OrgUnit created = load(id);
        recordChange(created, "CREATED", null, null, snapshot(created));
        return new Detail(created, ancestors(created), Map.of(), new Dependents(0, 0, 0, 0));
    }

    @Transactional
    public Detail update(UUID id, UpdateCommand c) {
        authz.require(MANAGE, id);
        OrgUnit before = load(id);
        checkVersion(before, c.version());
        String code = c.code() == null ? before.code() : validCode(c.code());
        String name = c.name() == null ? before.name() : validName(c.name());
        String shortName = c.shortName() == null ? before.shortName() : blankToNull(c.shortName());
        Map<String, String> attributes = c.attributes() == null ? before.attributes() : validAttributes(c.attributes());
        Map<String, String> branding = c.branding() == null ? before.branding() : validBranding(before.type(), c.branding());
        try {
            int n = jdbc.sql("""
                    UPDATE org_unit SET code = ?, name = ?, short_name = ?, attributes = CAST(? AS jsonb), branding = CAST(? AS jsonb),
                           updated_at = now(), updated_by = ?, version = version + 1
                    WHERE id = ? AND version = ?""")
                    .params(code, name, shortName, json.writeValueAsString(attributes), json.writeValueAsString(branding),
                            CurrentUser.require().userId(), id, c.version())
                    .update();
            if (n == 0) throw concurrent();
        } catch (DuplicateKeyException e) {
            throw duplicateCode(code);
        }
        OrgUnit after = load(id);
        recordChange(after, "UPDATED", null, snapshot(before), snapshot(after));
        return detailOf(after);
    }

    @Transactional
    public Detail move(UUID id, UUID newParentId, long version, String reason) {
        authz.require(MANAGE, id);
        authz.require(MANAGE, newParentId);
        OrgUnit unit = load(id);
        checkVersion(unit, version);
        if (java.util.Objects.equals(unit.parentId(), newParentId)) return detailOf(unit);
        OrgUnit newParent = null;
        if (newParentId == null) {
            if (!unit.type().mayBeTopLevel()) throw invalidHierarchy(unit.type(), null);
        } else {
            newParent = findUnit(newParentId).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "invalid_parent", "The selected parent does not exist in your organization."));
            if (newParent.path().equals(unit.path()) || newParent.path().startsWith(unit.path() + ".")) {
                throw ApiException.conflict("hierarchy_cycle", "A unit cannot be moved under itself or one of its own sub-units.");
            }
            if (!unit.type().allowedParents().contains(newParent.type())) throw invalidHierarchy(unit.type(), newParent.type());
            if (unit.status() == Status.ACTIVE && newParent.status() != Status.ACTIVE) {
                throw ApiException.conflict("parent_inactive", "An active unit cannot be moved under an inactive parent.");
            }
        }
        String newPath = OrgPaths.childPath(newParent == null ? null : newParent.path(), id);
        List<UUID> subtree = jdbc.sql("SELECT id FROM org_unit WHERE path <@ CAST(? AS ltree)").param(unit.path())
                .query(UUID.class).list();
        try {
            jdbc.sql("UPDATE org_unit SET parent_id = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                    .params(newParentId, CurrentUser.require().userId(), id).update();
            jdbc.sql("""
                    UPDATE org_unit SET path = CAST(? AS ltree) || CASE WHEN nlevel(path) > nlevel(CAST(? AS ltree))
                                                                    THEN subpath(path, nlevel(CAST(? AS ltree))) ELSE ''::ltree END
                    WHERE path <@ CAST(? AS ltree)""")
                    .params(newPath, unit.path(), unit.path(), unit.path()).update();
        } catch (DuplicateKeyException e) {
            throw duplicateCode(unit.code());
        }
        paths.evictAfterCommit(subtree);
        OrgUnit after = load(id);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("parentId", unit.parentId());
        from.put("parentName", unit.parentName());
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("parentId", newParentId);
        to.put("parentName", after.parentName());
        to.put("subtreeSize", subtree.size());
        recordChange(after, "MOVED", reason, from, to);
        return detailOf(after);
    }

    @Transactional
    public Detail setStatus(UUID id, Status target, long version, String reason) {
        authz.require(MANAGE, id);
        OrgUnit unit = load(id);
        checkVersion(unit, version);
        if (unit.status() == target) return detailOf(unit);
        if (target == Status.INACTIVE) {
            long activeChildren = jdbc.sql("SELECT count(*) FROM org_unit WHERE parent_id = ? AND status = 'ACTIVE'")
                    .param(id).query(Long.class).single();
            if (activeChildren > 0) {
                throw ApiException.conflict("has_active_children", "Deactivate the " + activeChildren + " active sub-unit(s) first.");
            }
        } else if (unit.parentId() != null && load(unit.parentId()).status() != Status.ACTIVE) {
            throw ApiException.conflict("parent_inactive", "Activate the parent unit first.");
        }
        jdbc.sql("UPDATE org_unit SET status = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?")
                .params(target.name(), CurrentUser.require().userId(), id).update();
        OrgUnit after = load(id);
        recordChange(after, target == Status.INACTIVE ? "DEACTIVATED" : "ACTIVATED", reason,
                Map.of("status", unit.status().name()), Map.of("status", target.name()));
        return detailOf(after);
    }

    @Transactional
    public void delete(UUID id, long version) {
        authz.require(MANAGE, id);
        OrgUnit unit = load(id);
        checkVersion(unit, version);
        Dependents d = dependents(id);
        if (d.any()) {
            throw ApiException.conflict("org_unit_has_dependents", "This unit is in use (" + d.children() + " sub-units, "
                    + d.roleAssignments() + " role assignments, " + d.featureOverrides()
                    + " feature settings). Deactivate it instead.");
        }
        recordChange(unit, "DELETED", null, snapshot(unit), null);
        jdbc.sql("DELETE FROM org_unit WHERE id = ? AND version = ?").params(id, version).update();
        paths.evictAfterCommit(List.of(id));
    }

    // --------------------------------------------------------------------------------------------- internals

    private static final String SELECT_UNIT = """
            SELECT u.id, u.parent_id, p.name AS parent_name, u.unit_type, u.code, u.name, u.short_name, u.status,
                   u.path::text AS path, u.attributes::text AS attributes, u.branding::text AS branding,
                   u.created_at, u.updated_at, u.version
            FROM org_unit u LEFT JOIN org_unit p ON p.id = u.parent_id""";

    /** Adds the caller's visibility restriction; false when the caller can see nothing. */
    private boolean appendVisibility(StringBuilder where, List<Object> params) {
        if (authz.has(READ)) return true;
        List<String> roots = new ArrayList<>();
        for (UUID root : authz.orgUnitScopesWith(READ)) {
            findPath(root).ifPresent(roots::add);
        }
        if (roots.isEmpty()) return false;
        where.append(" AND u.path <@ CAST(? AS ltree[])");
        params.add(roots.toArray(String[]::new));
        return true;
    }

    private Optional<String> findPath(UUID id) {
        return jdbc.sql("SELECT path::text FROM org_unit WHERE id = ?").param(id).query(String.class).optional();
    }

    private Optional<OrgUnit> findUnit(UUID id) {
        return jdbc.sql(SELECT_UNIT + " WHERE u.id = ?").param(id).query(this::mapUnit).optional();
    }

    /** Units of other tenants are invisible (RLS) and therefore "not found". */
    private OrgUnit load(UUID id) {
        return findUnit(id).orElseThrow(() -> ApiException.notFound("Organization unit"));
    }

    private Detail detailOf(OrgUnit unit) {
        Map<OrgUnitType, Long> childCounts = new TreeMap<>();
        jdbc.sql("SELECT unit_type, count(*) FROM org_unit WHERE parent_id = ? GROUP BY 1").param(unit.id())
                .query(rs -> { childCounts.put(OrgUnitType.valueOf(rs.getString(1)), rs.getLong(2)); });
        return new Detail(unit, ancestors(unit), childCounts, dependents(unit.id()));
    }

    private List<Ancestor> ancestors(OrgUnit unit) {
        List<UUID> ids = OrgPaths.ids(unit.path());
        List<UUID> upper = ids.subList(0, ids.size() - 1);
        if (upper.isEmpty()) return List.of();
        Map<UUID, Ancestor> byId = new LinkedHashMap<>();
        jdbc.sql("SELECT id, unit_type, code, name FROM org_unit WHERE id = ANY(?)").param(upper.toArray(UUID[]::new))
                .query(rs -> {
                    UUID aid = rs.getObject("id", UUID.class);
                    byId.put(aid, new Ancestor(aid, OrgUnitType.valueOf(rs.getString("unit_type")), rs.getString("code"), rs.getString("name")));
                });
        return upper.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private Dependents dependents(UUID id) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM org_unit WHERE parent_id = :id),
                       (SELECT count(*) FROM org_unit WHERE parent_id = :id AND status = 'ACTIVE'),
                       (SELECT count(*) FROM role_assignment WHERE scope_type = 'ORG_UNIT' AND scope_id = :id),
                       (SELECT count(*) FROM feature_flag_override WHERE org_unit_id = :id)""")
                .param("id", id)
                .query((rs, n) -> new Dependents(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
                .single();
    }

    private void recordChange(OrgUnit unit, String changeType, String reason, Map<String, Object> before, Map<String, Object> after) {
        UUID actor = CurrentUser.require().userId();
        jdbc.sql("""
                INSERT INTO org_unit_history (id, tenant_id, org_unit_id, change_type, changed_by, reason, before, after)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))""")
                .params(Ids.newId(), TenantContext.requireTenantId(), unit.id(), changeType, actor, blankToNull(reason),
                        before == null ? null : json.writeValueAsString(before), after == null ? null : json.writeValueAsString(after))
                .update();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("type", unit.type().name());
        details.put("code", unit.code());
        details.put("name", unit.name());
        if (unit.parentId() != null) details.put("parentId", unit.parentId().toString());
        if (reason != null && !reason.isBlank()) details.put("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
        String action = "org_unit." + changeType.toLowerCase();
        audit.recordSuccess(action, "org_unit", unit.id().toString(), details);
        outbox.append("org_unit", unit.id().toString(), action, details);
    }

    private static Map<String, Object> snapshot(OrgUnit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", u.type().name());
        m.put("code", u.code());
        m.put("name", u.name());
        m.put("shortName", u.shortName());
        m.put("status", u.status().name());
        m.put("parentId", u.parentId());
        m.put("attributes", u.attributes());
        m.put("branding", u.branding());
        return m;
    }

    private OrgUnit mapUnit(ResultSet rs, int n) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new OrgUnit(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("parent_name"),
                OrgUnitType.valueOf(rs.getString("unit_type")), rs.getString("code"), rs.getString("name"),
                rs.getString("short_name"), Status.valueOf(rs.getString("status")), rs.getString("path"),
                json.readValue(rs.getString("attributes"), STRING_MAP), json.readValue(rs.getString("branding"), STRING_MAP),
                rs.getTimestamp("created_at").toInstant(), updated.toInstant(), rs.getLong("version"));
    }

    private Map<String, Object> readObject(String raw) {
        return raw == null ? null : json.readValue(raw, OBJECT_MAP);
    }

    private static void checkVersion(OrgUnit unit, long version) {
        if (unit.version() != version) throw concurrent();
    }

    private static ApiException concurrent() {
        return ApiException.conflict("concurrent_modification", "The unit was changed by someone else. Reload and try again.");
    }

    private static ApiException duplicateCode(String code) {
        return ApiException.conflict("code_not_unique", "Another unit at this level already uses the code '" + code + "'.");
    }

    private static ApiException invalidHierarchy(OrgUnitType type, OrgUnitType parentType) {
        String allowed = type.allowedParents().isEmpty() ? "the top level only"
                : (type.mayBeTopLevel() ? "the top level or " : "") + String.join(" or ",
                        type.allowedParents().stream().map(OrgUnitType::defaultLabel).toList());
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_hierarchy",
                type.defaultLabel() + " cannot be placed " + (parentType == null ? "at the top level" : "under a " + parentType.defaultLabel())
                        + "; allowed: " + allowed + ".");
    }

    private static String validCode(String code) {
        if (code == null || !CODE.matcher(code.trim()).matches()) {
            throw ApiException.badRequest("invalid_code", "Code must be 1-63 characters: letters, digits, '-' or '_' (starting with a letter or digit).");
        }
        return code.trim();
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw ApiException.badRequest("invalid_name", "Name is required (at most 200 characters).");
        }
        return name.trim();
    }

    private static Map<String, String> validAttributes(Map<String, String> attributes) {
        if (attributes == null) return Map.of();
        Map<String, String> out = new TreeMap<>();
        for (var e : attributes.entrySet()) {
            if (!ATTRIBUTE_KEYS.contains(e.getKey())) {
                throw ApiException.badRequest("invalid_attribute", "Unknown attribute '" + e.getKey() + "'.");
            }
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.length() > 300) throw ApiException.badRequest("invalid_attribute", "Attribute '" + e.getKey() + "' is too long.");
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    private static Map<String, String> validBranding(OrgUnitType type, Map<String, String> branding) {
        if (branding == null || branding.isEmpty()) return Map.of();
        if (!type.isInstitution()) {
            throw ApiException.badRequest("branding_not_supported", "Branding is configured on universities and colleges.");
        }
        Map<String, String> out = new TreeMap<>();
        for (var e : branding.entrySet()) {
            if (!"accentColor".equals(e.getKey())) throw ApiException.badRequest("invalid_branding", "Unknown branding setting '" + e.getKey() + "'.");
            if (e.getValue() != null && !e.getValue().isBlank()) {
                if (!HEX.matcher(e.getValue()).matches()) throw ApiException.badRequest("invalid_branding", "Accent colour must be a #RRGGBB value.");
                out.put("accentColor", e.getValue().toLowerCase());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
