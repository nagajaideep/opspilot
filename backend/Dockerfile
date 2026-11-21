# ---------- Stage 1: build the jar with Maven ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Copy pom first and fetch dependencies: this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && mv target/opspilot-backend-*.jar app.jar

# ---------- Stage 2: small runtime image, no Maven/JDK/source ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Run as an unprivileged user, never root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
