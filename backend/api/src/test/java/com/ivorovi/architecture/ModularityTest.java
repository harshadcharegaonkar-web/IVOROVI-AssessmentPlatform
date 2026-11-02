package com.ivorovi.architecture;

import com.ivorovi.IvoroviApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Enforces module boundaries (ADR-0001) and renders module documentation into build/spring-modulith-docs. */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(IvoroviApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
