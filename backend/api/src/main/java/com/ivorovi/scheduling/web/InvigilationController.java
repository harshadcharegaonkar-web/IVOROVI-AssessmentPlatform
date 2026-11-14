package com.ivorovi.scheduling.web;

import com.ivorovi.scheduling.internal.InvigilationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exam/schedules/{id}")
@Tag(name = "Examination invigilation")
@Validated
class InvigilationController {

    @Schema(name = "CandidateIncidentRequest")
    record IncidentRequest(@NotNull UUID enrolmentId, @NotBlank String category, @NotBlank @Size(max = 1000) String note, UUID correctsId) {}

    private final InvigilationService invigilation;

    InvigilationController(InvigilationService invigilation) {
        this.invigilation = invigilation;
    }

    @GetMapping("/monitor")
    @PreAuthorize("@authz.hasAnywhere('exam.invigilate')")
    @Operation(summary = "Live state of every enrolled candidate (check-in, connection, progress; never answers or scores)")
    InvigilationService.Monitor monitor(@PathVariable UUID id) {
        return invigilation.monitor(id);
    }

    @GetMapping("/candidate-incidents")
    @PreAuthorize("@authz.hasAnywhere('exam.invigilate')")
    List<InvigilationService.Incident> incidents(@PathVariable UUID id) {
        return invigilation.incidents(id);
    }

    @PostMapping("/candidate-incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.hasAnywhere('exam.invigilate')")
    @Operation(summary = "Record a candidate incident (permanent; a correction is a new incident referring to the first)")
    InvigilationService.Incident record(@PathVariable UUID id, @Valid @RequestBody IncidentRequest r) {
        return invigilation.record(id, new InvigilationService.IncidentInput(r.enrolmentId(), r.category(), r.note(), r.correctsId()));
    }
}
