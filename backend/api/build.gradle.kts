plugins {
    java
    `java-test-fixtures`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.ivorovi"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Locked dependency versions (gradle.lockfile) make builds reproducible and let Trivy scan Java dependencies.
dependencyLocking { lockAllConfigurations() }

// Security overrides of Spring Boot-managed versions. Remove once the Boot BOM includes the fix.
// 2026-11-02: amqp-client 5.30.0 (Boot 4.1.2) has HIGH CVE-2026-63337/-69219/-69220/-75516, fixed by 5.34.0.
extra["rabbit-amqp-client.version"] = "5.37.0"

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
        mavenBom(libs.awssdk.bom.get().toString())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Annotations only at runtime; module verification (ArchUnit) runs in tests.
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation(libs.springdoc.webmvc)
    implementation("software.amazon.awssdk:s3") {
        // The synchronous client uses the Apache HTTP client; the Netty async client is unused.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.uuid.creator)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation(libs.greenmail)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Shared integration-test support (containers, signed test tokens, fixtures), also used by backend:devtools.
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-testcontainers")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-mail")
    testFixturesImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesImplementation("org.testcontainers:testcontainers-postgresql")
    testFixturesImplementation("org.testcontainers:testcontainers-rabbitmq")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // Exports the OpenAPI document consumed by the frontend api-client generator.
    systemProperty("ivorovi.openapi.export", rootProject.file("docs/api/openapi.json").absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.bootJar { archiveFileName = "ivorovi-api.jar" }
// Plain jar (classes only) so backend:devtools can depend on this project.
tasks.jar { archiveClassifier = "plain" }
