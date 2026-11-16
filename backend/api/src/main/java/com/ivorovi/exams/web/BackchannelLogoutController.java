package com.ivorovi.exams.web;

import com.ivorovi.exams.internal.CandidateSessions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KI-79: OIDC back-channel logout endpoint of the delivery boundary. Called by the candidate realm, never by browsers;
 * authenticated by the signed logout token alone (see {@link CandidateSessions}). In production it is reachable only from
 * the identity provider's network.
 */
@RestController
@RequestMapping("/api/v1/delivery")
@Tag(name = "Examination delivery")
class BackchannelLogoutController {

    private final CandidateSessions sessions;

    BackchannelLogoutController(CandidateSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping(path = "/backchannel-logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "OIDC back-channel logout from the candidate identity provider: the session ends immediately")
    ResponseEntity<Void> logout(@RequestParam(name = "logout_token", required = false) String token) {
        if (token == null || token.isBlank() || token.length() > 8192) return ResponseEntity.badRequest().build();
        return sessions.logout(token) ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
    }
}
