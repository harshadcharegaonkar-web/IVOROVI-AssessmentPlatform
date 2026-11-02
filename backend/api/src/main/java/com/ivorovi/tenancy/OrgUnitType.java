package com.ivorovi.tenancy;

import java.util.EnumSet;
import java.util.Set;

/**
 * Stable internal unit types (display labels are configurable per tenant and never change these values).
 * Allowed parents encode the approved hierarchy; levels are optional.
 */
public enum OrgUnitType {
    UNIVERSITY, COLLEGE, CAMPUS, DEPARTMENT;

    /** Types this type may be placed under; {@link #mayBeTopLevel()} covers "no parent". */
    public Set<OrgUnitType> allowedParents() {
        return switch (this) {
            case UNIVERSITY -> EnumSet.noneOf(OrgUnitType.class);
            case COLLEGE -> EnumSet.of(UNIVERSITY);
            case CAMPUS -> EnumSet.of(UNIVERSITY, COLLEGE);
            case DEPARTMENT -> EnumSet.of(UNIVERSITY, COLLEGE, CAMPUS);
        };
    }

    public boolean mayBeTopLevel() {
        return this == UNIVERSITY || this == COLLEGE;
    }

    /** Universities and colleges are "institutions": they carry branding and feature overrides. */
    public boolean isInstitution() {
        return this == UNIVERSITY || this == COLLEGE;
    }

    public String defaultLabel() {
        return switch (this) {
            case UNIVERSITY -> "University";
            case COLLEGE -> "College";
            case CAMPUS -> "Campus";
            case DEPARTMENT -> "Department";
        };
    }

    public String defaultPluralLabel() {
        return switch (this) {
            case UNIVERSITY -> "Universities";
            case COLLEGE -> "Colleges";
            case CAMPUS -> "Campuses";
            case DEPARTMENT -> "Departments";
        };
    }
}
