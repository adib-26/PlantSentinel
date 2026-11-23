# Render web service. Java 21 plus the native library ONNX Runtime needs.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/bas-intelligence-0.1.0-SNAPSHOT.jar app.jar
ENV SPRING_PROFILES_ACTIVE=demo
ENV BAS_KIRO_ASSISTANT_ENABLED=false
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
