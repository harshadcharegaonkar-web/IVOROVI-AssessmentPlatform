package com.ivorovi.devtools;

import com.ivorovi.IvoroviApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Development tools runner: the API's components plus dev-only tooling (synthetic data). Runs without a web
 * server. Never deployed. Guarded at runtime: tools refuse to run outside DEVELOPMENT/TEST environments.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "com.ivorovi")
@ComponentScan(basePackages = "com.ivorovi", excludeFilters = {
        // same exclusions as @SpringBootApplication (test configurations, auto-configurations) ...
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        // ... plus the API's own application class, whose configuration this class replaces
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = IvoroviApplication.class)})
@ConfigurationPropertiesScan("com.ivorovi")
@EnableCaching
public class DevtoolsApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(DevtoolsApplication.class).web(WebApplicationType.NONE).run(args);
    }
}
