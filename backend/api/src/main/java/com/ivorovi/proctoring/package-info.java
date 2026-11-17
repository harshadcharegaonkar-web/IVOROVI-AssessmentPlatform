/**
 * Proctoring and examination security (demo programme, add-on B; docs/phase-2/09-proctoring.md). Attaches to attempts of
 * the existing examination engine and never replaces it: candidate consent, browser signals, webcam snapshots kept as
 * time-limited evidence, deterministic rules that raise flags, and an invigilator review queue. Flags are signals, not
 * findings: only a person decides (dismiss, warn, record an incident or REQUEST termination through the existing four-eyes
 * workflow, via {@code scheduling.CandidateIncidents}). Uses only public module APIs.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Proctoring")
package com.ivorovi.proctoring;
