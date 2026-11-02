package com.ivorovi.support;

import java.util.UUID;

/** Identifiers from db/devdata + db/testdata fixtures. */
public final class Fixture {

    private Fixture() {}

    public static final UUID TENANT_A = UUID.fromString("0192a000-0000-7000-8000-00000000000a");
    public static final UUID TENANT_B = UUID.fromString("0192b000-0000-7000-8000-00000000000b");
    public static final UUID TENANT_SUSPENDED = UUID.fromString("0192c000-0000-7000-8000-00000000000c");

    public static final UUID INST_A1 = UUID.fromString("0192a000-0000-7000-8000-00000000a101");
    public static final UUID INST_A2 = UUID.fromString("0192a000-0000-7000-8000-00000000a102");
    public static final UUID INST_B1 = UUID.fromString("0192b000-0000-7000-8000-00000000b101");

    /** Token subject + tenant claim pairs. */
    public record Caller(String subject, UUID tenantId) {}

    public static final Caller ALICE_ORG_ADMIN_A = new Caller("0192a000-0000-7000-8000-0000000a0001", TENANT_A);
    public static final Caller ARUN_FACULTY_A1 = new Caller("0192a000-0000-7000-8000-0000000a0002", TENANT_A);
    public static final Caller ASHA_STUDENT_A1 = new Caller("0192a000-0000-7000-8000-0000000a0003", TENANT_A);
    public static final Caller INES_INST_ADMIN_A1 = new Caller("0192a000-0000-7000-8000-0000000a0004", TENANT_A);
    public static final Caller BELA_ORG_ADMIN_B = new Caller("0192b000-0000-7000-8000-0000000b0001", TENANT_B);
    public static final Caller CIRRUS_ADMIN_SUSPENDED = new Caller("sub-cirrus-admin", TENANT_SUSPENDED);
    public static final Caller EXPIRED_ADMIN_A = new Caller("sub-aurora-expired", TENANT_A);
    public static final Caller SUSPENDED_USER_A = new Caller("sub-aurora-suspended", TENANT_A);
    public static final Caller GHOST_A = new Caller("0192a000-0000-7000-8000-0000000a0009", TENANT_A);
}
