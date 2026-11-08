package com.ivorovi.questions.web;

import com.ivorovi.questions.internal.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bulk question import: template, scanned upload with preview and duplicate detection, and commit to drafts. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Question import")
@Validated
class QuestionImportController {

    private final ImportService imports;

    QuestionImportController(ImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/question-imports/template")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @Operation(summary = "CSV template with one example row per question type")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("ivorovi-question-import-template.csv", StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(ImportService.template().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/question-banks/{bankId}/imports")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    List<ImportService.Import> list(@PathVariable UUID bankId) {
        return imports.list(bankId);
    }

    @PostMapping(path = "/question-banks/{bankId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a CSV or XLSX (scanned first); every row is validated and checked for duplicates before anything is created")
    ImportService.Import upload(@PathVariable UUID bankId, @RequestPart("file") MultipartFile file) throws IOException {
        return imports.upload(bankId, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/question-imports/{id}")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    ImportService.Import read(@PathVariable UUID id) {
        return imports.read(id);
    }

    @GetMapping("/question-imports/{id}/rows")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Row outcomes (no answer keys)")
    List<ImportService.Row> rows(@PathVariable UUID id) {
        return imports.rows(id);
    }

    @PostMapping("/question-imports/{id}/commit")
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @Operation(summary = "Create drafts from valid rows (optionally near duplicates); only the uploader may commit; repeating returns the same result")
    ImportService.Import commit(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeNearDuplicates) {
        return imports.commit(id, includeNearDuplicates);
    }
}
