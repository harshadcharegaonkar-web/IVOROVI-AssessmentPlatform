package com.ivorovi.questions.web;

import com.ivorovi.questions.internal.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Question images: quarantined upload, malware scan, re-encoding, institution quotas and access-checked serving. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Question images")
@Validated
class QuestionAssetController {

    @Schema(name = "QuestionAssetQuotaRequest")
    record QuotaRequest(@Min(5_242_880L) @Max(1_099_511_627_776L) long limitBytes) {}

    private final AssetService assets;

    QuestionAssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/question-banks/{bankId}/assets")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    List<AssetService.Asset> list(@PathVariable UUID bankId) {
        return assets.list(bankId);
    }

    @PostMapping(path = "/question-banks/{bankId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasAnywhere('question.author')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a PNG or JPEG (max 5 MB, 4096×4096). It is quarantined and scanned; only clean images are re-encoded without metadata and served")
    AssetService.Asset upload(@PathVariable UUID bankId, @RequestPart("file") MultipartFile file,
                              @RequestParam(required = false) @Size(max = 250) String altText) throws IOException {
        return assets.upload(bankId, file.getOriginalFilename(), file.getBytes(), altText);
    }

    @GetMapping("/question-assets/{id}/content")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "A clean, re-encoded image to readers of its bank (unscanned, infected or failed images are refused)")
    ResponseEntity<byte[]> content(@PathVariable UUID id) {
        AssetService.Served s = assets.serve(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(s.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(s.bytes());
    }

    @GetMapping("/question-banks/{bankId}/asset-quota")
    @PreAuthorize("@authz.hasAnywhere('question.read')")
    @Operation(summary = "Image storage quota applying to the bank's institution, with current usage")
    AssetService.Quota quota(@PathVariable UUID bankId) {
        return assets.quota(bankId);
    }

    @PutMapping("/question-asset-quotas")
    @PreAuthorize("@authz.hasAnywhere('question.bank.manage')")
    @Operation(summary = "Set the organization-wide default image quota, or an institution's quota with orgUnitId")
    AssetService.Quota setQuota(@RequestParam(required = false) UUID orgUnitId, @Valid @RequestBody QuotaRequest r) {
        return assets.setQuota(orgUnitId, r.limitBytes());
    }
}
