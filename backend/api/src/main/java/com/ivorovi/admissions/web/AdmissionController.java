package com.ivorovi.admissions.web;

import com.ivorovi.admissions.internal.AdmissionDocuments;
import com.ivorovi.admissions.internal.ApplicationService;
import com.ivorovi.admissions.internal.CycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admissions (add-on E), staff side. Institution-level permissions are re-checked in the services. */
@RestController
@RequestMapping("/api/v1/admissions")
@Tag(name = "Admissions")
@Validated
class AdmissionController {

    @Schema(name = "AdmissionCycleRequest")
    record CycleRequest(@NotNull UUID institutionId, @NotNull UUID academicYearId, @NotNull UUID portalId, @NotNull @Size(max = 30) String code,
                        @NotNull @Size(max = 150) String name, @NotNull Instant opensAt, @NotNull Instant closesAt, Long version) {}

    @Schema(name = "AdmissionCycleStatusRequest")
    record StatusRequest(@NotNull CycleService.CycleStatus status, @NotNull Long version) {}

    @Schema(name = "AdmissionOfferingRequest")
    record OfferingRequest(UUID courseId, UUID batchId, @NotNull @Min(1) @Max(10000) Integer seats, Map<String, Integer> categoryQuotas,
                           @Min(0) @Max(100) Integer qualifyingWeight, @Min(0) @Max(100) Integer testWeight, @Size(max = 10) List<String> requiredDocuments, Long version) {}

    @Schema(name = "AdmissionRulesRequest")
    record RulesRequest(@NotNull @Size(max = 20) List<@Valid RuleRequest> rules) {}

    @Schema(name = "AdmissionRuleRequest")
    record RuleRequest(@NotNull CycleService.RuleKind kind, Map<String, Object> parameters) {}

    @Schema(name = "AdmissionDecisionRequest")
    record DecisionRequest(@NotNull ApplicationService.Decision decision, @Size(max = 500) String reason, @NotNull Long version) {}

    @Schema(name = "AdmissionDocumentVerificationRequest")
    record VerificationRequest(boolean verified, @Size(max = 500) String note) {}

    @Schema(name = "AdmissionDocumentCategory")
    record Category(String code, String label) {}

    private final CycleService cycles;
    private final ApplicationService applications;

    AdmissionController(CycleService cycles, ApplicationService applications) {
        this.cycles = cycles;
        this.applications = applications;
    }

    @GetMapping("/document-categories")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    List<Category> categories() {
        return CycleService.DOCUMENT_CATEGORIES.entrySet().stream().map(e -> new Category(e.getKey(), e.getValue())).toList();
    }

    @GetMapping("/cycles")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    List<CycleService.Cycle> cycles() {
        return cycles.list();
    }

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    CycleService.Cycle create(@Valid @RequestBody CycleRequest r) {
        return cycles.create(new CycleService.CycleCommand(r.institutionId(), r.academicYearId(), r.portalId(), r.code(), r.name(), r.opensAt(), r.closesAt(), null));
    }

    @GetMapping("/cycles/{id}")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    CycleService.Cycle cycle(@PathVariable UUID id) {
        return cycles.get(id);
    }

    @PatchMapping("/cycles/{id}")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    CycleService.Cycle update(@PathVariable UUID id, @Valid @RequestBody CycleRequest r) {
        return cycles.update(id, new CycleService.CycleCommand(r.institutionId(), r.academicYearId(), r.portalId(), r.code(), r.name(), r.opensAt(), r.closesAt(),
                r.version()));
    }

    @PostMapping("/cycles/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    @Operation(summary = "Open, close, reopen or complete a cycle")
    CycleService.Cycle status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return cycles.setStatus(id, r.status(), r.version());
    }

    @GetMapping("/cycles/{id}/offerings")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    List<CycleService.Offering> offerings(@PathVariable UUID id) {
        return cycles.offerings(id);
    }

    @PostMapping("/cycles/{id}/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    CycleService.Offering createOffering(@PathVariable UUID id, @Valid @RequestBody OfferingRequest r) {
        return cycles.createOffering(id, command(r));
    }

    @GetMapping("/offerings/{id}")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    CycleService.Offering offering(@PathVariable UUID id) {
        return cycles.offering(id);
    }

    @PatchMapping("/offerings/{id}")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    CycleService.Offering updateOffering(@PathVariable UUID id, @Valid @RequestBody OfferingRequest r) {
        return cycles.updateOffering(id, command(r));
    }

    private static CycleService.OfferingCommand command(OfferingRequest r) {
        return new CycleService.OfferingCommand(r.courseId(), r.batchId(), r.seats(), r.categoryQuotas(), r.qualifyingWeight(), r.testWeight(), r.requiredDocuments(),
                r.version());
    }

    @PutMapping("/offerings/{id}/rules")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    @Operation(summary = "Replace the typed eligibility rules of an offering (before any screening)")
    CycleService.Offering rules(@PathVariable UUID id, @Valid @RequestBody RulesRequest r) {
        return cycles.replaceRules(id, r.rules().stream().map(x -> new CycleService.RuleCommand(x.kind(), x.parameters())).toList());
    }

    @PostMapping("/offerings/{id}/screen")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    @Operation(summary = "Screen every submitted application of an offering (advice; staff decide)")
    Map<String, Integer> screenOffering(@PathVariable UUID id) {
        return applications.screenOffering(id);
    }

    @GetMapping("/cycles/{id}/applications")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    List<ApplicationService.Summary> applications(@PathVariable UUID id, @RequestParam(required = false) UUID offeringId,
                                                  @RequestParam(required = false) @Size(max = 20) String status) {
        return applications.list(id, offeringId, status);
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    ApplicationService.Detail application(@PathVariable UUID id) {
        return applications.detail(id);
    }

    @PostMapping("/applications/{id}/screen")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    ApplicationService.Detail screen(@PathVariable UUID id) {
        return applications.screen(id);
    }

    @PostMapping("/applications/{id}/decision")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    @Operation(summary = "Shortlist or reject after screening (a reason is required when the decision overrides the screening)")
    ApplicationService.Detail decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return applications.decide(id, r.decision(), r.reason(), r.version());
    }

    @GetMapping("/applications/{id}/documents/{documentId}/content")
    @PreAuthorize("@authz.hasAnywhere('admission.read')")
    @Operation(summary = "Download a clean document (quarantined, infected or failed scans are refused; audited)")
    ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable UUID documentId) {
        AdmissionDocuments.Download d = applications.download(id, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.content());
    }

    @PostMapping("/applications/{id}/documents/{documentId}/verification")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    ApplicationService.Detail verify(@PathVariable UUID id, @PathVariable UUID documentId, @Valid @RequestBody VerificationRequest r) {
        return applications.verifyDocument(id, documentId, r.verified(), r.note());
    }

    @PostMapping("/applications/{id}/documents/{documentId}/rescan")
    @PreAuthorize("@authz.hasAnywhere('admission.manage')")
    ApplicationService.Detail rescan(@PathVariable UUID id, @PathVariable UUID documentId) {
        return applications.rescanDocument(id, documentId);
    }
}
