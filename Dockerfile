# Build: docker build -t yard-tracker .
# Run:   docker run -p 8080:8080 --env-file yard.env yard-tracker     (variables: see docs/DEPLOY.md)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home yard
COPY --from=build /src/build/libs/yard-tracker-0.1.0.jar /app/app.jar
USER 10001
# The prod profile refuses to start unless every setting is production-safe (ProductionSafetyGuard).
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
