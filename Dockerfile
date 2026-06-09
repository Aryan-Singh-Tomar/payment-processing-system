# ---- Build stage ----
# Use a full JDK image because we need to compile and package the code.
FROM eclipse-temurin:17-jdk AS builder

# All subsequent paths are relative to /app
WORKDIR /app

# Copy dependency metadata first so dependency download is cached
# until pom.xml actually changes.
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn

# Download all dependencies. This is the slowest step; caching it is
# the single biggest optimization in this Dockerfile.
RUN ./mvnw dependency:go-offline -B

# Now copy the source. Source changes don't invalidate the dependency cache.
COPY src ./src

# Build the JAR. Skip tests because they need Testcontainers / live infrastructure
# that isn't available during image builds; CI runs tests separately.
RUN ./mvnw clean package -DskipTests

# ---- Runtime stage ----
# Use the smaller JRE image — we don't need the compiler at runtime.
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy only the JAR from the builder stage; leave everything else behind.
COPY --from=builder /app/target/*.jar app.jar

# Document the port the app listens on.
EXPOSE 8090

# Start the JVM directly (no shell) so signals propagate correctly.
ENTRYPOINT ["java", "-jar", "app.jar"]