package com.ivorovi.tenancy;

import java.time.LocalDate;

/** Organization rules for academic calendars (System Settings). */
public interface AcademicCalendarPolicy {

    /** Whether academic years of the same owner may overlap (default false). Terms within a year never overlap. */
    boolean allowOverlappingAcademicYears();

    /** Today's date in the organization's time zone (start and end dates of assignments are local dates). */
    LocalDate today();
}
