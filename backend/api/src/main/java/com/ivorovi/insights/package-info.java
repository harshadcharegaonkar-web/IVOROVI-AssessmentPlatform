/**
 * Insights (increment 1.7): executive, academic, student, staff and import dashboards and reusable operational
 * reports. Read-only aggregates over the other modules' records; every figure is limited to what the viewer may see
 * (tenant RLS plus the same scoped permissions as the underlying records).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Insights")
package com.ivorovi.insights;
