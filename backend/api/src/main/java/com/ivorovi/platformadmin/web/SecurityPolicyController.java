package com.ivorovi.platformadmin.web;

import com.ivorovi.platformadmin.internal.SecurityPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/security-policy")
@Tag(name = "Organization")
class SecurityPolicyController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "SecurityPolicyUpdateRequest")
    record UpdateRequest(@NotNull Boolean mfaRequired, @Min(5) @Max(1440) Integer idleMinutes, @Min(1) @Max(720) Integer absoluteHours, @NotNull Long version) {}

    private final SecurityPolicyService service;

    SecurityPolicyController(SecurityPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Organization security policy for staff sessions: second factor, idle and session limits. The password policy is platform-wide "
            + "(the shared staff realm) and not part of it (requires tenant.security.manage)")
    SecurityPolicyService.View get() {
        return service.view();
    }

    @PutMapping
    @Operation(summary = "Change the security policy. Stronger rules apply at once (APPLIED); any weaker rule needs a second administrator's approval "
            + "(PENDING_APPROVAL). Turning the second factor on requires the caller's own sign-in to have used one (requires tenant.security.manage)")
    SecurityPolicyService.Result update(@Valid @RequestBody UpdateRequest r) {
        return service.update(new SecurityPolicyService.Update(r.mfaRequired(), r.idleMinutes(), r.absoluteHours(), r.version()));
    }
}
