# Render web service. Java 21 plus the native library ONNX Runtime needs.
# The public site does not run Kiro. A free Kiro login cannot be kept on Render.
FROM eclipse-temurin:21-jdk-jammy AS build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL -o /tmp/maven.tgz https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz \
    && tar -xzf /tmp/maven.tgz -C /opt \
    && ln -s /opt/apache-maven-3.9.11/bin/mvn /usr/local/bin/mvn \
    && rm /tmp/maven.tgz \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
# Tests run in GitHub Actions. Skip them here so the image build only compiles the application.
RUN mvn -B -e -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/bas-intelligence-0.1.0-SNAPSHOT.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=demo
ENV BAS_KIRO_ASSISTANT_ENABLED=false
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
