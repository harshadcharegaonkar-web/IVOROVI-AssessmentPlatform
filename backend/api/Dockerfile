# Runtime image for the Ivorovi API. Packages the jar produced by `./gradlew :backend:api:bootJar`
# (CI builds and tests first; this keeps image builds reproducible and usable in air-gapped environments).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ivorovi && adduser -S -G ivorovi -H -s /sbin/nologin ivorovi
WORKDIR /app
COPY backend/api/build/libs/ivorovi-api.jar app.jar
USER ivorovi
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
