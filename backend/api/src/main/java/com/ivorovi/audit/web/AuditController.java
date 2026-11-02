package com.ivorovi.audit.web;

import com.ivorovi.platform.audit.AuditOutcome;
import com.ivorovi.platform.audit.AuditTrail;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
@Validated
@PreAuthorize("@authz.has('audit.read')")
class AuditController {

    private final AuditTrail trail;

    AuditController(AuditTrail trail) {
        this.trail = trail;
    }

    @GetMapping
    @Operation(summary = "Search the tenant audit trail (newest first)")
    PageResponse<AuditTrail.Entry> search(
            @RequestParam(required = false) @Pattern(regexp = "[a-z0-9_.]{1,100}") String action,
            @RequestParam(required = false) @Pattern(regexp = "[a-z0-9_.]{1,100}") String resourceType,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") int size) {
        int pageSize = PageResponse.clampSize(size);
        var result = trail.search(new AuditTrail.Query(action, resourceType, outcome, from, to), page, pageSize);
        int totalPages = (int) Math.ceil(result.total() / (double) pageSize);
        return new PageResponse<>(result.items(), page, pageSize, result.total(), totalPages);
    }

    @GetMapping("/verification")
    @Operation(summary = "Verify the integrity of the tenant's hash-chained audit trail")
    AuditTrail.Verification verify() {
        return trail.verify();
    }
}
