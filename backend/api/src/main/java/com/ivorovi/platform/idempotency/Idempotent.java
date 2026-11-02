package com.ivorovi.platform.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a create/submit endpoint as idempotent: callers must send an {@code Idempotency-Key} header (8–100 chars
 * of {@code [A-Za-z0-9_-]}). Retries with the same key and request replay the original response.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}
