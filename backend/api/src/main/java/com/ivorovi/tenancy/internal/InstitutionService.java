package com.ivorovi.tenancy.internal;

import com.ivorovi.platform.security.Authz;
import com.ivorovi.platform.security.Permission;
import com.ivorovi.tenancy.InstitutionLookup;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InstitutionService implements InstitutionLookup {

    private final InstitutionRepository repository;
    private final Authz authz;

    InstitutionService(InstitutionRepository repository, Authz authz) {
        this.repository = repository;
        this.authz = authz;
    }

    @Override
    public Optional<InstitutionSummary> find(UUID institutionId) {
        return repository.findById(institutionId)
                .map(i -> new InstitutionSummary(i.getId(), i.getCode(), i.getName(), i.getStatus()));
    }

    /** Lists institutions visible to the caller: all of the tenant for tenant-wide readers, else assigned ones. */
    public Page<Institution> listVisible(String q, Pageable pageable) {
        String key = Permission.INSTITUTION_READ.key();
        String query = q == null || q.isBlank() ? null : q.trim();
        if (authz.has(key)) return repository.search(query, pageable);
        Set<UUID> scoped = authz.institutionsWith(key);
        if (scoped.isEmpty()) return Page.empty(pageable);
        return repository.searchWithin(scoped, query, pageable);
    }

    public Optional<Institution> get(UUID id) {
        return repository.findById(id);
    }
}
