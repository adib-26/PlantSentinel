# Render web service. Java 21, ONNX Runtime's native library, and Kiro CLI.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 curl ca-certificates unzip \
    && curl -fsSL https://cli.kiro.dev/install -o /tmp/kiro-install.sh \
    && Q_INSTALL_GLOBAL=1 bash /tmp/kiro-install.sh --force \
    && rm -rf /tmp/kiro-install.sh /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/bas-intelligence-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /src/.kiro .kiro
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=demo
ENV BAS_KIRO_ASSISTANT_ENABLED=true
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
