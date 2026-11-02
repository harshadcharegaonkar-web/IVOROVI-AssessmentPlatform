package com.ivorovi.identity.web;

import com.ivorovi.platform.security.CurrentUser;
import com.ivorovi.platform.security.Grant;
import com.ivorovi.platform.security.IvoroviPrincipal;
import com.ivorovi.platform.tenancy.TenantDirectory;
import com.ivorovi.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Identity")
class MeController {

    record TenantView(UUID id, String code, String name, Map<String, Object> branding) {}

    record GrantView(String permission, String role, String scopeType, UUID scopeId) {}

    record MeResponse(UUID userId, String email, String displayName, TenantView tenant, List<String> roles,
                      List<GrantView> grants) {}

    private final TenantDirectory tenants;
    private final JsonMapper json;
    private final com.ivorovi.identity.internal.UserService users;

    MeController(TenantDirectory tenants, JsonMapper json, com.ivorovi.identity.internal.UserService users) {
        this.tenants = tenants;
        this.json = json;
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "Current user, tenant (with branding) and effective scoped permissions")
    MeResponse me() {
        IvoroviPrincipal p = CurrentUser.require();
        // The web app loads /me right after sign-in; auth_time is when the identity provider authenticated the user.
        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                instanceof com.ivorovi.platform.security.IvoroviAuthentication a && a.getCredentials() != null) {
            Object authTime = a.getCredentials().getClaims().get("auth_time");
            java.time.Instant at = authTime instanceof java.time.Instant i ? i
                    : authTime instanceof Number n ? java.time.Instant.ofEpochSecond(n.longValue()) : null;
            users.recordSignIn(p.userId(), at);
        }
        var t = tenants.find(p.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
        Map<String, Object> branding = json.readValue(t.brandingJson(), new TypeReference<Map<String, Object>>() {});
        return new MeResponse(p.userId(), p.email(), p.displayName(),
                new TenantView(t.id(), t.code(), t.name(), branding),
                p.roleCodes().stream().sorted().toList(),
                p.grants().stream().map(MeController::view).toList());
    }

    private static GrantView view(Grant g) {
        return new GrantView(g.permission(), g.roleCode(), g.scopeType().name(), g.scopeId());
    }
}
