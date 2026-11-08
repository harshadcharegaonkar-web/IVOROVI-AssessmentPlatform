package com.ivorovi.questions.web;

import com.ivorovi.questions.internal.BankService;
import com.ivorovi.questions.internal.QuestionService;
import com.ivorovi.questions.internal.TaxonomyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Question banks, institution approval-policy floors, taxonomy and the question type catalogue. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Question banks")
@Validated
class QuestionBankController {

    @Schema(name = "QuestionPolicyInput")
    record PolicyInput(@NotNull @Pattern(regexp = "STANDARD|HIGH_STAKES") String approvalPolicy, @Min(1) @Max(3) int requiredReviews, boolean approverDistinct) {
        BankService.Policy toPolicy() {
            return new BankService.Policy(approvalPolicy, requiredReviews, approverDistinct);
        }
    }

    @Schema(name = "QuestionBankRequest")
    record BankRequest(UUID orgUnitId, @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String code, @NotBlank @Size(max = 150) String name,
                       @Size(max = 1000) String description,
                       @Size(max = 6) List<@Pattern(regexp = "ADMISSION_EXAM|ADMISSION_SCREENING|ACADEMIC|PLACEMENT|PRACTICE") String> intendedUses,
                       @NotNull @Valid PolicyInput policy, Boolean freeTags,
                       @DecimalMin("0") @DecimalMax("1000") BigDecimal defaultMarks, @DecimalMin("0") @DecimalMax("1000") BigDecimal defaultNegativeMarks,
                       @Pattern(regexp = "ACTIVE|ARCHIVED") String status, Long version) {
        BankService.BankInput toInput() {
            return new BankService.BankInput(orgUnitId, code, name, description, intendedUses, policy.toPolicy(), freeTags, defaultMarks, defaultNegativeMarks, status, version);
        }
    }

    @Schema(name = "QuestionSubjectRequest")
    record SubjectRequest(UUID orgUnitId, @NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 150) String name, UUID academicSubjectId) {}

    @Schema(name = "QuestionTopicRequest")
    record TopicRequest(@NotNull UUID subjectId, UUID parentId, @NotBlank @Size(max = 150) String name) {}

    @Schema(name = "LearningOutcomeRequest")
    record OutcomeRequest(@NotNull UUID subjectId, @NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 500) String description,
                          @Size(max = 20) String bloomLevel) {}

    @Schema(name = "DifficultyLevelRequest")
    record DifficultyRequest(@NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_]+") String code, @NotBlank @Size(max = 60) String label,
                             @Min(1) @Max(100) int rank) {}

    @Schema(name = "QuestionAttributeRequest")
    record AttributeRequest(@NotBlank @Size(max = 60) @Pattern(regexp = "[a-z][a-z0-9_]*") String key, @NotBlank @Size(max = 100) String label,
                            @NotNull @Pattern(regexp = "TEXT|NUMBER|BOOLEAN|CHOICE|DATE") String type, @Size(max = 50) List<@Size(max = 100) String> allowedValues,
                            boolean requiredForApproval) {}

    @Schema(name = "TaxonomyStatusRequest")
    record StatusRequest(boolean active) {}

    @Schema(name = "QuestionTypeInfo")
    record TypeInfo(String code, String label) {}

    private final BankService banks;
    private final TaxonomyService taxonomy;
    private final QuestionService questions;

    QuestionBankController(BankService banks, TaxonomyService taxonomy, QuestionService questions) {
        this.banks = banks;
        this.taxonomy = taxonomy;
        this.questions = questions;
    }

    // ---------------------------------------------------------------------------------------------------- banks

    @GetMapping("/question-banks")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Question banks of the units the caller may read")
    List<BankService.Bank> list() {
        return banks.list();
    }

    @PostMapping("/question-banks")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a bank (admission banks are always high-stakes; institution floors apply)")
    BankService.Bank create(@Valid @RequestBody BankRequest r) {
        return banks.create(r.toInput());
    }

    @GetMapping("/question-banks/{id}")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    BankService.Bank get(@PathVariable UUID id) {
        return banks.get(id);
    }

    @PutMapping("/question-banks/{id}")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @Operation(summary = "Update a bank; a weaker approval policy needs a second administrator's approval before it applies")
    BankService.Bank update(@PathVariable UUID id, @Valid @RequestBody BankRequest r) {
        return banks.update(id, r.toInput());
    }

    // ---------------------------------------------------------------------------------------------------- policy floors

    @GetMapping("/question-policy-floors")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Minimum approval policies set by institutions (banks may be stricter, never weaker)")
    List<BankService.Floor> floors() {
        return banks.floors();
    }

    @PutMapping("/question-policy-floors/{orgUnitId}")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @Operation(summary = "Set an institution's minimum approval policy (weakening needs a second administrator's approval)")
    BankService.Floor setFloor(@PathVariable UUID orgUnitId, @Valid @RequestBody PolicyInput r) {
        return banks.setFloor(orgUnitId, r.toPolicy());
    }

    // ---------------------------------------------------------------------------------------------------- taxonomy

    @GetMapping("/question-taxonomy")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    TaxonomyService.Taxonomy taxonomy() {
        return taxonomy.all();
    }

    @PostMapping("/question-taxonomy/subjects")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyService.Subject subject(@Valid @RequestBody SubjectRequest r) {
        return taxonomy.createSubject(r.orgUnitId(), r.code(), r.name(), r.academicSubjectId());
    }

    @PostMapping("/question-taxonomy/topics")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a topic, or a subtopic when parentId names a topic")
    TaxonomyService.Topic topic(@Valid @RequestBody TopicRequest r) {
        return taxonomy.createTopic(r.subjectId(), r.parentId(), r.name());
    }

    @PostMapping("/question-taxonomy/learning-outcomes")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyService.Outcome outcome(@Valid @RequestBody OutcomeRequest r) {
        return taxonomy.createOutcome(r.subjectId(), r.code(), r.description(), r.bloomLevel());
    }

    @PostMapping("/question-taxonomy/difficulty-levels")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyService.Difficulty difficulty(@Valid @RequestBody DifficultyRequest r) {
        return taxonomy.createDifficulty(r.code(), r.label(), r.rank());
    }

    @PostMapping("/question-taxonomy/attributes")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Define an institution metadata field for questions")
    TaxonomyService.Attribute attribute(@Valid @RequestBody AttributeRequest r) {
        return taxonomy.createAttribute(r.key(), r.label(), r.type(), r.allowedValues(), r.requiredForApproval());
    }

    @PutMapping("/question-taxonomy/{kind}/{id}/status")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate or reactivate a taxonomy entry (entries are never deleted)")
    void status(@PathVariable @Pattern(regexp = "subjects|topics|learning-outcomes|difficulty-levels|attributes") String kind, @PathVariable UUID id,
                @RequestBody StatusRequest r) {
        taxonomy.setStatus(kind, id, r.active());
    }

    // ---------------------------------------------------------------------------------------------------- types

    @GetMapping("/question-types")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    List<TypeInfo> types() {
        return questions.types().stream().map(t -> new TypeInfo(t.code(), t.label())).toList();
    }
}
