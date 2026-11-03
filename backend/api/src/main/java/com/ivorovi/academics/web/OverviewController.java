package com.ivorovi.academics.web;

import com.ivorovi.academics.internal.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Academic dashboard KPIs and the structure tree (both limited to the caller's read scope). */
@RestController
@RequestMapping("/api/v1/academics")
@Tag(name = "Academic structure")
class OverviewController {

    private final OverviewService overview;

    OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/overview")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Academic KPIs from the caller's authorized data (cached; invalidated by every academic change)")
    OverviewService.Kpis kpis() {
        return overview.kpis();
    }

    @GetMapping("/tree")
    @PreAuthorize("@authz.hasAnywhere('academics.read')")
    @Operation(summary = "Program → course → batch tree")
    OverviewService.Tree tree(@RequestParam(required = false) UUID ownerOrgUnitId, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return overview.tree(ownerOrgUnitId, includeInactive);
    }
}
