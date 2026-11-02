package com.ivorovi.notifications.web;

import com.ivorovi.notifications.NotificationService;
import com.ivorovi.platform.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
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
                            long totalElements, int totalPages, long unread) {}

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List my notifications (newest first)")
    NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                          @RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "20") int size) {
        int s = PageResponse.clampSize(size);
        var p = service.mine(unreadOnly, page, s);
        return new NotificationPage(p.items(), page, s, p.total(), (int) Math.ceil(p.total() / (double) s), p.unread());
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

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    Map<String, Integer> readAll() {
        return Map.of("updated", service.markAllRead());
    }
}
