package com.ivorovi.students.web;

import com.ivorovi.students.internal.StudentNumbering;
import com.ivorovi.students.internal.StudentService;
import com.ivorovi.students.internal.StudentService.OptionKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Student numbering rules (per university/college) and configurable option lists. */
@RestController
@RequestMapping("/api/v1/student-settings")
@Tag(name = "Students")
@Validated
class StudentSettingsController {

    @Schema(name = "StudentNumberRuleRequest")
    record RuleRequest(@NotNull StudentNumbering.Mode mode, @Size(max = 100) String template, @Size(max = 40) String manualPattern,
                       @Size(max = 40) String registrationPattern, Long version) {}

    @Schema(name = "StudentOptionRequest")
    record OptionRequest(@NotNull OptionKind kind, @NotNull @Size(min = 2, max = 40) String code, @NotNull @Size(min = 1, max = 100) String label) {}

    @Schema(name = "StudentOptionStatusRequest")
    record OptionStatusRequest(@NotNull Boolean active) {}

    private final StudentNumbering numbering;
    private final StudentService students;

    StudentSettingsController(StudentNumbering numbering, StudentService students) {
        this.numbering = numbering;
        this.students = students;
    }

    @GetMapping("/numbering-rules")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Student ID rules in force for each visible university and college (own or inherited)")
    List<StudentNumbering.RuleView> rules() {
        return numbering.rules();
    }

    @PutMapping("/numbering-rules/{orgUnitId}")
    @PreAuthorize("@authz.hasAnywhere('student.manage')")
    @Operation(summary = "Set the numbering rule of a university or college (generated template or manual format mask)")
    StudentNumbering.RuleView saveRule(@PathVariable UUID orgUnitId, @Valid @RequestBody RuleRequest r) {
        return numbering.save(orgUnitId, new StudentNumbering.RuleCommand(r.mode(), r.template(), r.manualPattern(), r.registrationPattern(), r.version()));
    }

    @GetMapping("/options")
    @PreAuthorize("@authz.hasAnywhere('student.read')")
    @Operation(summary = "Admission types, admission categories and document categories (built-in and configured)")
    List<StudentService.Option> options() {
        return students.options();
    }

    @PostMapping("/options")
    @PreAuthorize("@authz.has('tenant.settings.manage')")
    @Operation(summary = "Add an organization-defined option")
    StudentService.Option addOption(@Valid @RequestBody OptionRequest r) {
        return students.addOption(r.kind(), r.code(), r.label());
    }

    @PostMapping("/options/{kind}/{code}/status")
    @PreAuthorize("@authz.has('tenant.settings.manage')")
    @Operation(summary = "Activate or deactivate an organization-defined option (never deleted)")
    List<StudentService.Option> optionStatus(@PathVariable OptionKind kind, @PathVariable String code, @Valid @RequestBody OptionStatusRequest r) {
        students.setOptionActive(kind, code, r.active());
        return students.options();
    }
}
