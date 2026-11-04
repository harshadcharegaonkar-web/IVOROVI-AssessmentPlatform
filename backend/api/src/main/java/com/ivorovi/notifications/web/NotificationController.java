package com.ivorovi.notifications.web;

import com.ivorovi.notifications.NotificationService;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notifications. Other users' notifications are never addressable (404). */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@Validated
class NotificationController {

    record NotificationPage(java.util.List<NotificationService.Notification> items, int page, int size,
                            long totalElements, int totalPages, long unread, Map<String, Long> unreadByCategory) {}

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List my notifications (newest first); state ALL, UNREAD or READ; optional category")
    NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                          @RequestParam(required = false) @Pattern(regexp = "ALL|UNREAD|READ") String state,
                          @RequestParam(required = false) @Size(max = 30) String category,
                          @RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var p = service.mine(state != null ? state : unreadOnly ? "UNREAD" : "ALL", category, page, s);
        return new NotificationPage(p.items(), page, s, p.total(), (int) Math.ceil(p.total() / (double) s), p.unread(), service.unreadByCategory());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Number of my unread notifications")
    Map<String, Long> unreadCount() {
        return Map.of("unread", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one of my notifications as read")
    ResponseEntity<Void> read(@PathVariable UUID id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unread")
    @Operation(summary = "Mark one of my notifications as unread")
    ResponseEntity<Void> unread(@PathVariable UUID id) {
        service.markUnread(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    Map<String, Integer> readAll() {
        return Map.of("updated", service.markAllRead());
    }
}
