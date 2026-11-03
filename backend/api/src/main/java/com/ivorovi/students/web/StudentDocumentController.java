package com.ivorovi.students.web;

import com.ivorovi.students.internal.DocumentService;
import com.ivorovi.students.internal.DocumentService.Verification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Student documents: upload into quarantine, scan, verification, download of clean files only, removal. */
@RestController
@RequestMapping("/api/v1/students/{studentId}/documents")
@Tag(name = "Student documents")
@Validated
class StudentDocumentController {

    @Schema(name = "DocumentVerificationRequest")
    record VerificationRequest(@NotNull Verification status, @Size(max = 500) String note) {}

    @Schema(name = "DocumentRemovalRequest")
    record RemovalRequest(@NotNull @Size(min = 1, max = 500) String reason) {}

    private final DocumentService documents;

    StudentDocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('student.document.read')")
    @Operation(summary = "Documents of the student with scan and verification state")
    List<DocumentService.DocumentView> list(@PathVariable UUID studentId, @RequestParam(defaultValue = "false") boolean includeRemoved) {
        return documents.list(studentId, includeRemoved);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasAnywhere('student.document.manage')")
    @Operation(summary = "Upload a PDF, JPEG or PNG (max 5 MB) into quarantine; it becomes downloadable only after a clean malware scan")
    ResponseEntity<List<DocumentService.DocumentView>> upload(@PathVariable UUID studentId, @RequestPart("file") MultipartFile file,
                                                              @RequestParam @Size(max = 40) String category, @RequestParam(required = false) @Size(max = 200) String title)
            throws IOException {
        documents.upload(studentId, category, title, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(201).body(documents.list(studentId, false));
    }

    @GetMapping("/{documentId}/content")
    @PreAuthorize("@authz.hasAnywhere('student.document.read')")
    @Operation(summary = "Download a clean document (quarantined, infected or failed scans are refused; audited)")
    ResponseEntity<byte[]> download(@PathVariable UUID studentId, @PathVariable UUID documentId) {
        DocumentService.Download d = documents.download(studentId, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.content());
    }

    @PostMapping("/{documentId}/verification")
    @PreAuthorize("@authz.hasAnywhere('student.document.manage')")
    @Operation(summary = "Verify or reject a clean document")
    List<DocumentService.DocumentView> verify(@PathVariable UUID studentId, @PathVariable UUID documentId, @Valid @RequestBody VerificationRequest r) {
        documents.verify(studentId, documentId, r.status(), r.note());
        return documents.list(studentId, false);
    }

    @PostMapping("/{documentId}/rescan")
    @PreAuthorize("@authz.hasAnywhere('student.document.manage')")
    @Operation(summary = "Queue a document whose scan failed for another scan")
    List<DocumentService.DocumentView> rescan(@PathVariable UUID studentId, @PathVariable UUID documentId) {
        documents.rescan(studentId, documentId);
        return documents.list(studentId, false);
    }

    @PostMapping("/{documentId}/remove")
    @PreAuthorize("@authz.hasAnywhere('student.document.manage')")
    @Operation(summary = "Remove a document (kept as a record; the file is purged after the retention period)")
    List<DocumentService.DocumentView> remove(@PathVariable UUID studentId, @PathVariable UUID documentId, @Valid @RequestBody RemovalRequest r) {
        documents.remove(studentId, documentId, r.reason());
        return documents.list(studentId, false);
    }
}
