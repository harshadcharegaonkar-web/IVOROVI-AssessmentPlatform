# Software bill of materials: demo programme (D-19)

Owner: Session 3. Scope: the 14-day demo programme only. This is **not** a production approval.

D-19 says: no dependency without approval; exact versions and licences recorded here; no integration marked complete until its workflow is demonstrated.

How this file is kept up to date:
- **Top part (by hand):** the approved D-19 components and every container image of the demo stack. Update it in the same commit that adds or changes one.
- **Bottom part (generated):** every Java runtime library of the API and every JavaScript production dependency of the frontend, from what the build actually resolves.
  - Regenerate with `python3 scripts/demo/sbom.py`.
  - Check with `--check`.
  - It needs a resolved Gradle cache and `frontend/node_modules`.

Last updated: 2026-11-16 (Day 1), main `68ed941`.

## 1. Components approved by D-19

| Component | Approval | Exact version | Licence | Where / how it is used | Licence notes | Status |
|---|---|---|---|---|---|---|
| ClamAV (clamd) | Approved with conditions | `clamav/clamav:1.4` = ClamAV **1.4.6**, image `sha256:4cc4350d3fded380caf1799e76c0d1c93f3fa40af99c0cb2862ec0c9b117718a`; signatures in the image: daily 28184 (2026-11-15) | GPL-2.0-only | Separate service (`deploy/compose/compose.scan.yaml`). The API talks to it over the clamd TCP protocol (`ClamAvScanner`), so no ClamAV code is linked | Running an unmodified GPL service and speaking its protocol creates no obligation for our code. Redistributing the image means offering its source, which the upstream image provides | **Integrated** on `ds/s3-clamav-sbom`. Real-clamd tests pass (`ClamAvRealScannerIT`); finding QA-DP-1 open |
| Moodle | Approved with conditions (separate service, genuine LTI 1.3) | **Not chosen yet.** The image route is escalated to the product owner. Options: (a) the official `moodlehq/moodle-php-apache` runtime plus Moodle source, built on the demo host (recommended by A); (b) `bitnamilegacy/moodle` pinned by digest, for local development only, labelled **unmaintained** | GPL-3.0-or-later | Separate service; LTI 1.3 platform for the `lti` module | Separate service, no linking. Our LTI tool talks to it only through LTI 1.3 and HTTP | Pending decision |
| CodeMirror 6 | Approved | To be recorded by Session 2 when integrated | MIT | Browser code editor for coding assessments (bundled into the exam app) | MIT notice in the bundle's licence file | Not integrated |
| OpenPDF | **Conditionally** approved | To be recorded by Session 2 **before** integration | LGPL-2.1-or-later / MPL-2.0 (dual) | PDF generation (admit cards, scorecards) in the API | Before integration, verify: the exact version's licence files; using it as an unmodified, separately replaceable jar (LGPL relinking); notices in the distribution | Not integrated; verification outstanding |
| Prometheus | Approved (restricted access, no sensitive data) | To be recorded by Session 2 | Apache-2.0 | Metrics scraping (separate service) | — | Not integrated |
| Grafana | Approved (restricted access, no sensitive data) | To be recorded by Session 2 | **AGPL-3.0-only** (Grafana OSS since v8) | Dashboards (separate service) | Running it unmodified is fine. **Modifying it and offering it over a network** triggers AGPL source obligations. Flagged for the product owner; no change to the approval | Not integrated |

## 2. Container images of the demo stack

From `deploy/compose/compose.yaml`, its overlays and the Dockerfiles. Digests are as pulled on 2026-11-16. Images are referenced by tag in compose unless pinned by digest.

| Image | Role | Version / digest | Licence of the software |
|---|---|---|---|
| `postgres:16.15` | Database (application and Keycloak schemas) | `sha256:f9a7b9ebf6f56201f949db745e47bc423c0f0f8294bafe991cc10bf74b029657` | PostgreSQL License |
| `valkey/valkey:8.1-alpine` | Cache, rate limits | `sha256:2602ace2be6569bc2bbb06f873776516ab9089821525be34f4305c67eb9db02a` | BSD-3-Clause |
| `rabbitmq:4.3-management-alpine` | Message broker (outbox) | `sha256:eb338b3fc8f5c251028c0022ac0de3fe1db2d599d1c6a08b45898f756f248881` | MPL-2.0 |
| `chrislusf/seaweedfs:4.51` | S3-compatible object store | `sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8` | Apache-2.0 |
| `axllent/mailpit:v1.33` | Development mail catcher | `sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce` | MIT |
| `keycloak/keycloak:26.8.1` | Identity (staff and candidate realms); a second instance is approved as the demo institutional IdP | `sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332` | Apache-2.0 |
| `clamav/clamav:1.4` | Malware scanning (pinned by digest) | `sha256:4cc4350d3fded380caf1799e76c0d1c93f3fa40af99c0cb2862ec0c9b117718a` | GPL-2.0-only |
| `eclipse-temurin:21-jre-alpine` | Base of the API image | Digest recorded at the demo-host build (not pulled in this environment) | GPL-2.0 with Classpath Exception |
| `nginx:1.30.6-alpine3.24` | Base of the staff and exam app images; also the TLS edge of the demo host (approved, certificates from the host, no certbot) | Digest recorded at the demo-host build | BSD-2-Clause |

Pending, not approved: the demo consumer runtime image (`python:3.13-slim` proposed; escalated to the product owner).

## 3. Libraries (generated)

<!-- generated:begin (scripts/demo/sbom.py) -->

### Java runtime libraries of the API (199)

Resolved `runtimeClasspath` of `backend/api`; licence as declared in the artifact's POM (or its parent).

| Group | Artifact | Version | Licence |
|---|---|---|---|
| ch.qos.logback | logback-classic | 1.5.38 | EPL-2.0; LGPL-2.1-only |
| ch.qos.logback | logback-core | 1.5.38 | EPL-2.0; LGPL-2.1-only |
| com.fasterxml | classmate | 1.7.3 | Apache License, Version 2.0 |
| com.fasterxml.jackson | jackson-bom | 2.21.7 | Apache License, Version 2.0 |
| com.fasterxml.jackson.core | jackson-annotations | 2.21 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core | jackson-core | 2.21.7 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core | jackson-databind | 2.21.7 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.dataformat | jackson-dataformat-yaml | 2.21.7 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.datatype | jackson-datatype-jsr310 | 2.21.7 | The Apache Software License, Version 2.0 |
| com.github.f4b6a3 | uuid-creator | 6.1.1 | MIT License |
| com.nimbusds | nimbus-jose-jwt | 10.9.1 | The Apache Software License, Version 2.0 |
| com.rabbitmq | amqp-client | 5.37.0 | AL 2.0; GPL v2; MPL 2.0 |
| com.sun.istack | istack-commons-runtime | 4.1.2 | Eclipse Distribution License - v 1.0 |
| com.zaxxer | HikariCP | 7.0.2 | The Apache Software License, Version 2.0 |
| commons-logging | commons-logging | 1.3.6 | Apache-2.0 |
| io.lettuce | lettuce-core | 7.5.2.RELEASE | MIT |
| io.micrometer | micrometer-commons | 1.17.2 | The Apache Software License, Version 2.0 |
| io.micrometer | micrometer-core | 1.17.2 | The Apache Software License, Version 2.0 |
| io.micrometer | micrometer-jakarta9 | 1.17.2 | The Apache Software License, Version 2.0 |
| io.micrometer | micrometer-observation | 1.17.2 | The Apache Software License, Version 2.0 |
| io.micrometer | micrometer-registry-prometheus | 1.17.2 | The Apache Software License, Version 2.0 |
| io.netty | netty-buffer | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec-base | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec-compression | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec-dns | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec-marshalling | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-codec-protobuf | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-common | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-handler | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-resolver | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-resolver-dns | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-transport | 4.2.19.Final | Apache License, Version 2.0 |
| io.netty | netty-transport-native-unix-common | 4.2.19.Final | Apache License, Version 2.0 |
| io.projectreactor | reactor-core | 3.8.8 | Apache License, Version 2.0 |
| io.prometheus | prometheus-metrics-config | 1.7.0 | The Apache Software License, Version 2.0 |
| io.prometheus | prometheus-metrics-core | 1.7.0 | The Apache Software License, Version 2.0 |
| io.prometheus | prometheus-metrics-exposition-formats | 1.7.0 | The Apache Software License, Version 2.0 |
| io.prometheus | prometheus-metrics-exposition-textformats | 1.7.0 | The Apache Software License, Version 2.0 |
| io.prometheus | prometheus-metrics-model | 1.7.0 | The Apache Software License, Version 2.0 |
| io.prometheus | prometheus-metrics-tracer-common | 1.7.0 | The Apache Software License, Version 2.0 |
| io.swagger.core.v3 | swagger-annotations-jakarta | 2.2.55 | Apache License 2.0 |
| io.swagger.core.v3 | swagger-core-jakarta | 2.2.55 | Apache License 2.0 |
| io.swagger.core.v3 | swagger-models-jakarta | 2.2.55 | Apache License 2.0 |
| jakarta.activation | jakarta.activation-api | 2.1.4 | EDL 1.0 |
| jakarta.annotation | jakarta.annotation-api | 3.0.0 | EPL 2.0; GPL2 w/ CPE |
| jakarta.inject | jakarta.inject-api | 2.0.1 | The Apache Software License, Version 2.0 |
| jakarta.mail | jakarta.mail-api | 2.1.5 | EPL 2.0; GPL2 w/ CPE; EDL 1.0 |
| jakarta.persistence | jakarta.persistence-api | 3.2.0 | Eclipse Public License v. 2.0; Eclipse Distribution License v. 1.0 |
| jakarta.transaction | jakarta.transaction-api | 2.0.1 | EPL 2.0; GPL2 w/ CPE |
| jakarta.validation | jakarta.validation-api | 3.1.1 | Apache License 2.0 |
| jakarta.xml.bind | jakarta.xml.bind-api | 4.0.5 | Eclipse Distribution License - v 1.0 |
| net.bytebuddy | byte-buddy | 1.18.15 | Apache License, Version 2.0 |
| org.antlr | antlr4-runtime | 4.13.2 | BSD-3-Clause |
| org.apache.commons | commons-lang3 | 3.20.0 | Apache-2.0 |
| org.apache.httpcomponents.client5 | httpclient5 | 5.6.5 | Apache License, Version 2.0 |
| org.apache.httpcomponents.core5 | httpcore5 | 5.4.4 | Apache License, Version 2.0 |
| org.apache.httpcomponents.core5 | httpcore5-h2 | 5.4.4 | Apache License, Version 2.0 |
| org.apache.logging.log4j | log4j-api | 2.25.5 | Apache-2.0 |
| org.apache.logging.log4j | log4j-to-slf4j | 2.25.5 | Apache-2.0 |
| org.apache.tomcat.embed | tomcat-embed-core | 11.0.27 | Apache License, Version 2.0 |
| org.apache.tomcat.embed | tomcat-embed-el | 11.0.27 | Apache License, Version 2.0 |
| org.apache.tomcat.embed | tomcat-embed-websocket | 11.0.27 | Apache License, Version 2.0 |
| org.aspectj | aspectjweaver | 1.9.25.1 | Eclipse Public License - v 2.0 |
| org.checkerframework | checker-qual | 3.55.1 | The MIT License |
| org.eclipse.angus | angus-activation | 2.0.3 | EDL 1.0 |
| org.eclipse.angus | angus-mail | 2.0.5 | EPL 2.0; GPL2 w/ CPE; EDL 1.0 |
| org.flywaydb | flyway-core | 12.4.0 | Apache License, Version 2.0 |
| org.flywaydb | flyway-database-postgresql | 12.4.0 | Apache License, Version 2.0 |
| org.glassfish.jaxb | jaxb-core | 4.0.9 | Eclipse Distribution License - v 1.0 |
| org.glassfish.jaxb | jaxb-runtime | 4.0.9 | Eclipse Distribution License - v 1.0 |
| org.glassfish.jaxb | txw2 | 4.0.9 | Eclipse Distribution License - v 1.0 |
| org.hdrhistogram | HdrHistogram | 2.2.2 | Public Domain, per Creative Commons CC0; BSD-2-Clause |
| org.hibernate.models | hibernate-models | 1.1.2 | Apache License Version 2.0 |
| org.hibernate.orm | hibernate-core | 7.4.13.Final | Apache License, Version 2.0 |
| org.hibernate.orm | hibernate-platform | 7.4.13.Final | Apache License, Version 2.0 |
| org.hibernate.validator | hibernate-validator | 9.1.5.Final | Apache License 2.0 |
| org.jboss.logging | jboss-logging | 3.6.3.Final | Apache License 2.0 |
| org.jspecify | jspecify | 1.0.1 | The Apache License, Version 2.0 |
| org.postgresql | postgresql | 42.7.14 | BSD-2-Clause |
| org.reactivestreams | reactive-streams | 1.0.4 | MIT-0 |
| org.slf4j | jul-to-slf4j | 2.0.20 | MIT |
| org.slf4j | slf4j-api | 2.0.20 | MIT |
| org.springdoc | springdoc-openapi-starter-common | 3.2.0 | The Apache License, Version 2.0 |
| org.springdoc | springdoc-openapi-starter-webmvc-api | 3.2.0 | The Apache License, Version 2.0 |
| org.springdoc | springdoc-openapi-starter-webmvc-ui | 3.2.0 | The Apache License, Version 2.0 |
| org.springframework | spring-aop | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-aspects | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-beans | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-context | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-context-support | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-core | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-expression | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-jdbc | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-messaging | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-orm | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-oxm | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-tx | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-web | 7.0.10 | Apache License, Version 2.0 |
| org.springframework | spring-webmvc | 7.0.10 | Apache License, Version 2.0 |
| org.springframework.amqp | spring-amqp | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.amqp | spring-rabbit | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-actuator | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-actuator-autoconfigure | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-amqp | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-autoconfigure | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-cache | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-data-commons | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-data-jpa | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-data-redis | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-flyway | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-health | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-hibernate | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-http-converter | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-jackson | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-jdbc | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-jpa | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-mail | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-micrometer-metrics | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-micrometer-observation | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-netty | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-persistence | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-security | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-security-oauth2-resource-server | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-servlet | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-sql | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-actuator | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-amqp | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-cache | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-data-jpa | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-data-redis | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-flyway | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-jackson | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-jdbc | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-logging | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-mail | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-micrometer-metrics | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-oauth2-resource-server | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-security | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-tomcat | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-tomcat-runtime | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-validation | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-starter-webmvc | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-tomcat | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-transaction | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-validation | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-web-server | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.boot | spring-boot-webmvc | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.data | spring-data-commons | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.data | spring-data-jpa | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.data | spring-data-keyvalue | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.data | spring-data-redis | 4.1.2 | Apache License, Version 2.0 |
| org.springframework.modulith | spring-modulith-api | 2.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-config | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-core | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-crypto | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-oauth2-core | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-oauth2-jose | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-oauth2-resource-server | 7.1.2 | Apache License, Version 2.0 |
| org.springframework.security | spring-security-web | 7.1.2 | Apache License, Version 2.0 |
| org.webjars | swagger-ui | 5.34.0 | Apache-2.0 |
| org.webjars | webjars-locator-lite | 1.1.5 | MIT |
| org.yaml | snakeyaml | 2.6 | Apache License, Version 2.0 |
| redis.clients.authentication | redis-authx-core | 0.1.1-beta2 | MIT |
| software.amazon.awssdk | annotations | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | apache5-client | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | arns | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | auth | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | aws-core | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | aws-query-protocol | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | aws-xml-protocol | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | checksums | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | checksums-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | crt-core | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | endpoints-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | eventstream | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | http-auth | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | http-auth-aws | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | http-auth-aws-eventstream | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | http-auth-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | http-client-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | identity-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | json-utils | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | metrics-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | profiles | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | protocol-core | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | regions | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | retries | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | retries-spi | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | s3 | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | sdk-core | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | third-party-jackson-core | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | utils | 2.57.8 | Apache License, Version 2.0 |
| software.amazon.awssdk | utils-lite | 2.57.8 | Apache License, Version 2.0 |
| tools.jackson | jackson-bom | 3.1.7 | Apache License, Version 2.0 |
| tools.jackson.core | jackson-core | 3.1.7 | The Apache Software License, Version 2.0 |
| tools.jackson.core | jackson-databind | 3.1.7 | The Apache Software License, Version 2.0 |

### JavaScript production dependencies of the frontend (99)

`pnpm licenses list --prod -r` over `frontend/` (staff app, exam app and shared packages); bundled into the apps.

| Package | Version | Licence |
|---|---|---|
| @floating-ui/core | 1.8.0 | MIT |
| @floating-ui/dom | 1.8.0 | MIT |
| @floating-ui/react-dom | 2.1.9 | MIT |
| @floating-ui/utils | 0.2.12 | MIT |
| @fontsource-variable/inter | 5.3.0 | OFL-1.1 |
| @hookform/resolvers | 7.0.1 | MIT |
| @radix-ui/number | 1.1.3 | MIT |
| @radix-ui/primitive | 1.2.0 | MIT |
| @radix-ui/react-accessible-icon | 1.2.0 | MIT |
| @radix-ui/react-accordion | 1.3.1 | MIT |
| @radix-ui/react-alert-dialog | 1.2.1 | MIT |
| @radix-ui/react-arrow | 1.1.17 | MIT |
| @radix-ui/react-aspect-ratio | 1.2.0 | MIT |
| @radix-ui/react-avatar | 1.3.1 | MIT |
| @radix-ui/react-checkbox | 1.4.1 | MIT |
| @radix-ui/react-collapsible | 1.2.1 | MIT |
| @radix-ui/react-collection | 1.2.0 | MIT |
| @radix-ui/react-compose-refs | 1.1.5 | MIT |
| @radix-ui/react-context | 1.3.0 | MIT |
| @radix-ui/react-context-menu | 2.4.1 | MIT |
| @radix-ui/react-dialog | 1.3.1 | MIT |
| @radix-ui/react-direction | 1.2.0 | MIT |
| @radix-ui/react-dismissable-layer | 1.2.1 | MIT |
| @radix-ui/react-dropdown-menu | 2.2.1 | MIT |
| @radix-ui/react-focus-guards | 1.2.0 | MIT |
| @radix-ui/react-focus-scope | 1.3.1 | MIT |
| @radix-ui/react-form | 0.3.0 | MIT |
| @radix-ui/react-hover-card | 1.2.1 | MIT |
| @radix-ui/react-id | 1.1.4 | MIT |
| @radix-ui/react-label | 2.1.17 | MIT |
| @radix-ui/react-menu | 2.2.1 | MIT |
| @radix-ui/react-menubar | 1.2.1 | MIT |
| @radix-ui/react-navigation-menu | 1.4.1 | MIT |
| @radix-ui/react-one-time-password-field | 0.2.1 | MIT |
| @radix-ui/react-password-toggle-field | 0.2.1 | MIT |
| @radix-ui/react-popover | 1.3.1 | MIT |
| @radix-ui/react-popper | 1.4.0 | MIT |
| @radix-ui/react-portal | 1.2.0 | MIT |
| @radix-ui/react-presence | 1.1.11 | MIT |
| @radix-ui/react-primitive | 2.2.0 | MIT |
| @radix-ui/react-progress | 1.2.1 | MIT |
| @radix-ui/react-radio-group | 1.5.1 | MIT |
| @radix-ui/react-roving-focus | 1.2.1 | MIT |
| @radix-ui/react-scroll-area | 1.4.0 | MIT |
| @radix-ui/react-select | 2.4.1 | MIT |
| @radix-ui/react-separator | 1.2.0 | MIT |
| @radix-ui/react-slider | 1.6.1 | MIT |
| @radix-ui/react-slot | 1.5.0 | MIT |
| @radix-ui/react-switch | 1.4.1 | MIT |
| @radix-ui/react-tabs | 1.2.1 | MIT |
| @radix-ui/react-toast | 1.3.1 | MIT |
| @radix-ui/react-toggle | 1.2.1 | MIT |
| @radix-ui/react-toggle-group | 1.2.1 | MIT |
| @radix-ui/react-toolbar | 1.2.1 | MIT |
| @radix-ui/react-tooltip | 1.4.1 | MIT |
| @radix-ui/react-use-callback-ref | 1.1.5 | MIT |
| @radix-ui/react-use-controllable-state | 1.3.0 | MIT |
| @radix-ui/react-use-effect-event | 0.0.5 | MIT |
| @radix-ui/react-use-escape-keydown | 1.1.6 | MIT |
| @radix-ui/react-use-is-hydrated | 0.1.3 | MIT |
| @radix-ui/react-use-layout-effect | 1.1.4 | MIT |
| @radix-ui/react-use-previous | 1.1.4 | MIT |
| @radix-ui/react-use-rect | 1.1.4 | MIT |
| @radix-ui/react-use-size | 1.1.5 | MIT |
| @radix-ui/react-visually-hidden | 1.2.13 | MIT |
| @radix-ui/rect | 1.1.3 | MIT |
| @remix-run/route-pattern | 0.22.1 | MIT |
| @standard-schema/utils | 0.3.0 | MIT |
| @tanstack/query-core | 5.105.1 | MIT |
| @tanstack/react-query | 5.105.1 | MIT |
| @types/react | 19.3.0 | MIT |
| @types/react-dom | 19.3.0 | MIT |
| aria-hidden | 1.2.6 | MIT |
| clsx | 2.1.1 | MIT |
| commander | 8.3.0 | MIT |
| cookie-es | 3.1.1 | MIT |
| csstype | 3.2.3 | MIT |
| detect-node-es | 1.1.0 | MIT |
| get-nonce | 1.0.1 | MIT |
| jwt-decode | 4.0.0 | MIT |
| katex | 0.16.47 | MIT |
| lucide-react | 1.66.0 | ISC |
| oidc-client-ts | 3.5.0 | Apache-2.0 |
| openapi-fetch | 0.17.0 | MIT |
| openapi-typescript-helpers | 0.1.0 | MIT |
| radix-ui | 1.8.1 | MIT |
| react | 19.3.1 | MIT |
| react-dom | 19.3.1 | MIT |
| react-hook-form | 7.91.0 | MIT |
| react-oidc-context | 3.3.1 | MIT |
| react-remove-scroll | 2.7.2 | MIT |
| react-remove-scroll-bar | 2.3.8 | MIT |
| react-router | 8.5.1 | MIT |
| react-style-singleton | 2.2.3 | MIT |
| scheduler | 0.28.0 | MIT |
| tslib | 2.9.1 | 0BSD |
| use-callback-ref | 1.3.3 | MIT |
| use-sidecar | 1.1.3 | MIT |
| zod | 4.6.7 | MIT |

<!-- generated:end -->
