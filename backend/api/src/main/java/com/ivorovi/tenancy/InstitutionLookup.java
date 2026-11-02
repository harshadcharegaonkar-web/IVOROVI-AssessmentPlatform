package com.ivorovi.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Published API of the tenancy module. Lookups are restricted to the current tenant. */
public interface InstitutionLookup {

    record InstitutionSummary(UUID id, String code, String name, String status) {}

    Optional<InstitutionSummary> find(UUID institutionId);
}
