package com.ivorovi.tenancy.web;

import com.ivorovi.platform.web.ApiException;
import com.ivorovi.platform.web.PageResponse;
import com.ivorovi.tenancy.internal.Institution;
import com.ivorovi.tenancy.internal.InstitutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/institutions")
@Tag(name = "Institutions")
@Validated
class InstitutionController {

    record InstitutionView(UUID id, String code, String name, String status, Instant createdAt) {
        static InstitutionView of(Institution i) {
            return new InstitutionView(i.getId(), i.getCode(), i.getName(), i.getStatus(), i.getCreatedAt());
        }
    }

    private final InstitutionService service;

    InstitutionController(InstitutionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('institution.read')")
    @Operation(summary = "List institutions visible to the caller (search, sort, pagination)")
    PageResponse<InstitutionView> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|code|createdAt") String sort,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        var pageable = PageRequest.of(page, PageResponse.clampSize(size),
                Sort.by(Sort.Direction.fromString(direction), sort));
        return PageResponse.of(service.listVisible(q, pageable), InstitutionView::of);
    }

    /** Institutions of other tenants are invisible (RLS) and yield 404, never 403, to avoid existence leaks. */
    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasInInstitution('institution.read', #id)")
    @Operation(summary = "Get one institution")
    InstitutionView get(@PathVariable UUID id) {
        return service.get(id).map(InstitutionView::of).orElseThrow(() -> ApiException.notFound("Institution"));
    }
}
