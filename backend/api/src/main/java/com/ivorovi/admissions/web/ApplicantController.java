package com.ivorovi.admissions.web;

import com.ivorovi.admissions.internal.ApplicantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The applicant portal (add-on E): candidate-realm endpoints on the delivery boundary. The applicant is the signed-in
 * candidate of the portal (exams.ExamCandidates); they see and change only their own profile and applications.
 */
@RestController
@RequestMapping("/api/v1/delivery/portals/{key}/admissions")
@Tag(name = "Applicant portal")
@Validated
class ApplicantController {

    @Schema(name = "ApplicantProfileRequest")
    record ProfileRequest(@Size(max = 100) String firstName, @Size(max = 100) String middleName, @Size(max = 100) String lastName, LocalDate dateOfBirth,
                          @Size(max = 20) String gender, @Size(max = 60) String nationality, @Size(max = 25) String mobile, @Size(max = 500) String address,
                          @Size(max = 40) String category, Long version) {}

    @Schema(name = "ApplicationRequest")
    record ApplicationRequest(UUID offeringId, BigDecimal qualifyingPercent, @Size(max = 150) String qualifyingBoard, Long version) {}

    @Schema(name = "ApplicationVersionRequest")
    record VersionRequest(Long version) {}

    private final ApplicantService applicants;

    ApplicantController(ApplicantService applicants) {
        this.applicants = applicants;
    }

    @GetMapping
    @Operation(summary = "My profile, the open programmes of this portal and my applications")
    ApplicantService.Portal portal(@PathVariable String key) {
        return applicants.portal(key);
    }

    @PutMapping("/profile")
    ApplicantService.Profile profile(@PathVariable String key, @Valid @RequestBody ProfileRequest r) {
        return applicants.saveProfile(key, new ApplicantService.ProfileCommand(r.firstName(), r.middleName(), r.lastName(), r.dateOfBirth(), r.gender(),
                r.nationality(), r.mobile(), r.address(), r.category(), r.version()));
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicantService.MyApplication start(@PathVariable String key, @Valid @RequestBody ApplicationRequest r) {
        return applicants.saveApplication(key, null, new ApplicantService.ApplicationCommand(r.offeringId(), r.qualifyingPercent(), r.qualifyingBoard(), null));
    }

    @PutMapping("/applications/{id}")
    ApplicantService.MyApplication save(@PathVariable String key, @PathVariable UUID id, @Valid @RequestBody ApplicationRequest r) {
        return applicants.saveApplication(key, id, new ApplicantService.ApplicationCommand(null, r.qualifyingPercent(), r.qualifyingBoard(), r.version()));
    }

    @PostMapping(path = "/applications/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a PDF, JPEG or PNG (max 5 MB) into quarantine; it is accepted only after a clean malware scan")
    ApplicantService.MyApplication upload(@PathVariable String key, @PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                          @RequestParam @Size(max = 40) String category) throws IOException {
        return applicants.upload(key, id, category, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/applications/{id}/submit")
    ApplicantService.MyApplication submit(@PathVariable String key, @PathVariable UUID id, @RequestBody VersionRequest r) {
        return applicants.submit(key, id, r.version());
    }

    @PostMapping("/applications/{id}/withdraw")
    ApplicantService.MyApplication withdraw(@PathVariable String key, @PathVariable UUID id, @RequestBody VersionRequest r) {
        return applicants.withdraw(key, id, r.version());
    }
}
