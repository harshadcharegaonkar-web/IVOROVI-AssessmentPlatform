package com.ivorovi.integrations.web;

import com.ivorovi.integrations.internal.WebhookDispatcher;
import com.ivorovi.integrations.internal.WebhookService;
import com.ivorovi.platform.tenancy.TenantContext;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Webhook endpoints and deliveries. The signing secret is returned only on create and rotate. */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks")
@Validated
class WebhookController {

    @Schema(name = "WebhookEndpointRequest")
    record Request(@Size(max = 100) String name, @Size(max = 500) String url, @Size(max = 20) List<@Size(max = 60) String> eventTypes, Boolean includeIdentifiers,
                   Long version) {}

    @Schema(name = "WebhookStatusRequest")
    record StatusRequest(@NotNull @Pattern(regexp = "ACTIVE|PAUSED") String status) {}

    @Schema(name = "WebhookTestResult")
    record TestResult(boolean delivered, WebhookService.Delivery delivery) {}

    private final WebhookService service;
    private final WebhookDispatcher dispatcher;

    WebhookController(WebhookService service, WebhookDispatcher dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    @GetMapping("/event-types")
    @Operation(summary = "Subscribable event types")
    List<com.ivorovi.integrations.internal.WebhookEvents.Type> eventTypes() {
        return service.eventTypes();
    }

    @GetMapping
    @Operation(summary = "Webhook endpoints with status and 30-day delivery counts")
    List<WebhookService.View> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an endpoint (HTTPS, SSRF-checked); returns the signing secret once")
    WebhookService.Created create(@RequestBody Request r) {
        return service.create(new WebhookService.Command(r.name(), r.url(), r.eventTypes(), r.includeIdentifiers(), null));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Endpoint details")
    WebhookService.View get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change name, URL, event types or identifier opt-in")
    WebhookService.View update(@PathVariable UUID id, @RequestBody Request r) {
        return service.update(id, new WebhookService.Command(r.name(), r.url(), r.eventTypes(), r.includeIdentifiers(), r.version()));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Pause or resume (resume also re-enables an automatically disabled endpoint)")
    WebhookService.View status(@PathVariable UUID id, @RequestBody StatusRequest r) {
        return service.setStatus(id, r.status());
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Replace the signing secret; returns the new secret once")
    Map<String, String> rotate(@PathVariable UUID id) {
        return Map.of("signingSecret", service.rotateSecret(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an endpoint and its delivery history")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send a signed webhook.ping now and return the outcome")
    TestResult test(@PathVariable UUID id) {
        UUID delivery = service.queuePing(id);
        boolean ok = dispatcher.dispatchOne(TenantContext.requireTenantId(), delivery);
        return new TestResult(ok, service.delivery(id, delivery));
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "Delivery history (newest first), optionally by status")
    PageResponse<WebhookService.Delivery> deliveries(@PathVariable UUID id, @RequestParam(required = false) @Pattern(regexp = "PENDING|DELIVERED|FAILED") String status,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        WebhookService.Page<WebhookService.Delivery> p = service.deliveries(id, status, page, size);
        return new PageResponse<>(p.items(), page, size, p.total(), (int) Math.ceil(p.total() / (double) size));
    }

    @GetMapping("/{id}/deliveries/{deliveryId}")
    @Operation(summary = "One delivery with its payload")
    WebhookService.Delivery delivery(@PathVariable UUID id, @PathVariable UUID deliveryId) {
        return service.delivery(id, deliveryId);
    }

    @PostMapping("/{id}/deliveries/{deliveryId}/replay")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Queue the same payload again as a new delivery")
    WebhookService.Delivery replay(@PathVariable UUID id, @PathVariable UUID deliveryId) {
        return service.replay(id, deliveryId);
    }
}
