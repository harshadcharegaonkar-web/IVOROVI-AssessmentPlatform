package com.ivorovi.devtools.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Command-line entry point, active only with the {@code seed} profile. Runs once and exits.
 * Example: {@code ./scripts/seed.sh --ivorovi.seed.tenants=3}
 */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private final SyntheticDataGenerator generator;
    private final ConfigurableApplicationContext context;
    private final SyntheticDataGenerator.Request request;

    SeedRunner(SyntheticDataGenerator generator, ConfigurableApplicationContext context,
               @Value("${ivorovi.seed.tenants:3}") int tenants,
               @Value("${ivorovi.seed.institutions-per-tenant:3}") int institutions,
               @Value("${ivorovi.seed.users-per-tenant:50}") int users,
               @Value("${ivorovi.seed.random-seed:42}") long seed) {
        this.generator = generator;
        this.context = context;
        this.request = new SyntheticDataGenerator.Request(tenants, institutions, users, seed);
    }

    @Override
    public void run(ApplicationArguments args) {
        var result = generator.generate(request);
        log.info("SYNTHETIC DATA GENERATED: tenants={} orgUnits={} users={} roleAssignments={}",
                result.tenantIds().size(), result.orgUnits(), result.users(), result.roleAssignments());
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
