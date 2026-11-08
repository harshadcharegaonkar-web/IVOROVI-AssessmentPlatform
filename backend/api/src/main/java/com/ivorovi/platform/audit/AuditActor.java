package com.ivorovi.platform.audit;

import java.util.UUID;

/**
 * An authenticated actor that is not a staff user (e.g. a candidate of the exam application). Audit entries record its
 * id and a prefixed subject ({@code candidate:<subject>}) so candidate actions can never be confused with staff actions.
 */
public interface AuditActor {

    UUID auditActorId();

    String auditActorSubject();
}
