/**
 * Platform kernel: tenancy, security, errors, audit, outbox, feature-independent infrastructure ports.
 * Open module: every business module may depend on it; it must never depend on a business module.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN, displayName = "Platform kernel")
package com.ivorovi.platform;
