package com.ivorovi.identity.web;

import com.ivorovi.identity.internal.InvitationService;
import com.ivorovi.identity.internal.UserService;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Invitation administration, plus the two public endpoints used by the acceptance page. */
@RestController
@Tag(name = "Invitations")
@Validated
class InvitationController {

    @Schema(name = "InvitationTokenRequest")
    record TokenRequest(@NotNull @Size(min = 43, max = 43) String token) {}

    @Schema(name = "InvitationAcceptRequest")
    record AcceptRequest(@NotNull @Size(min = 43, max = 43) String token, @Size(max = 256) String password) {
        @Override public String toString() { return "AcceptRequest[redacted]"; }
    }

    private final InvitationService invitations;
    private final UserService users;

    InvitationController(InvitationService invitations, UserService users) {
        this.invitations = invitations;
        this.users = users;
    }

    @GetMapping("/api/v1/invitations")
    @PreAuthorize("@authz.hasAnywhere('user.read')")
    @Operation(summary = "Invitations of users visible to the caller")
    PageResponse<InvitationService.InvitationView> list(
            @RequestParam(required = false) InvitationService.State status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var r = invitations.list(status, q, page, s);
        return new PageResponse<>(r.items(), page, s, r.total(), (int) Math.ceil(r.total() / (double) s));
    }

    @PostMapping("/api/v1/invitations/{id}/revoke")
    @PreAuthorize("@authz.hasAnywhere('user.manage')")
    @Operation(summary = "Revoke an open invitation; its link stops working immediately")
    ResponseEntity<Void> revoke(@PathVariable UUID id) {
        invitations.revoke(id, users);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/public/invitations/lookup")
    @Operation(summary = "Public: describe an invitation for the acceptance page (token in the body, never in the URL)")
    InvitationService.Lookup lookup(@Valid @RequestBody TokenRequest r) {
        return invitations.lookup(r.token());
    }

    @PostMapping("/api/v1/public/invitations/accept")
    @Operation(summary = "Public: accept an invitation, creating the login with the chosen password")
    ResponseEntity<Void> accept(@Valid @RequestBody AcceptRequest r) {
        invitations.accept(r.token(), r.password() == null ? null : r.password().toCharArray());
        return ResponseEntity.noContent().build();
    }
}
