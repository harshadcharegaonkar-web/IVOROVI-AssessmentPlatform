package com.ivorovi.identity.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * One-shot operator command (runbook {@code privileged-role-bootstrap.md}); present only when
 * {@code ivorovi.ops.bootstrap-privileged-role.enabled=true}. Runs the bootstrap and exits the process with 0 on success,
 * 2 on refusal. Never logs credentials.
 */
@Component
@ConditionalOnProperty(name = "ivorovi.ops.bootstrap-privileged-role.enabled", havingValue = "true")
class PrivilegedRoleBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrivilegedRoleBootstrapRunner.class);
    private static final String P = "ivorovi.ops.bootstrap-privileged-role.";

    private final PrivilegedRoleBootstrap bootstrap;
    private final Environment env;
    private final ApplicationContext context;

    PrivilegedRoleBootstrapRunner(PrivilegedRoleBootstrap bootstrap, Environment env, ApplicationContext context) {
        this.bootstrap = bootstrap;
        this.env = env;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            String unit = env.getProperty(P + "org-unit-id");
            PrivilegedRoleBootstrap.Result r = bootstrap.bootstrap(new PrivilegedRoleBootstrap.Command(UUID.fromString(env.getRequiredProperty(P + "tenant-id")),
                    env.getRequiredProperty(P + "user-email"), env.getRequiredProperty(P + "role-code"), unit == null || unit.isBlank() ? null : UUID.fromString(unit),
                    env.getRequiredProperty(P + "operator"), env.getRequiredProperty(P + "ticket"), env.getRequiredProperty(P + "reason")));
            log.info("Privileged role bootstrap done: assignment {} ({}, {})", r.assignmentId(), r.roleCode(), String.join(",", r.privileged()));
            code = 0;
        } catch (RuntimeException e) {
            log.error("Privileged role bootstrap refused: {}", e.getMessage());
            code = 2;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
