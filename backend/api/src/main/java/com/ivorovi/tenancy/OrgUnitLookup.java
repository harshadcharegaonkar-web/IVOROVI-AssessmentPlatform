package com.ivorovi.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Published API of the tenancy module. All lookups are restricted to the current tenant (RLS). */
public interface OrgUnitLookup {

    record OrgUnitRef(UUID id, UUID parentId, OrgUnitType type, String code, String name, String status) {}

    Optional<OrgUnitRef> find(UUID orgUnitId);

    /** The unit's ancestors and the unit itself, top-level first. Empty if the unit is not visible. */
    List<OrgUnitRef> ancestorsOrSelf(UUID orgUnitId);
}
