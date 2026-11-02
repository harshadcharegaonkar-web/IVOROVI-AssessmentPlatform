package com.ivorovi.identity.web;

import com.ivorovi.identity.internal.AssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only role catalog for assignment (custom roles and permission editing come in increment 1.3). */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles")
class RoleController {

    private final AssignmentService assignments;

    RoleController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnywhere('role.read')")
    @Operation(summary = "System roles that can be assigned, with their permissions")
    List<AssignmentService.RoleView> list() {
        return assignments.assignableRoles();
    }
}
