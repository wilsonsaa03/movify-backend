# ─────────────────────────────────────────────────────────────
#  MoviFY backend — imagen para Dokploy.
#  Las pruebas corren en GitHub Actions; aquí solo se compila el jar.
#  Los secretos (DB_PASSWORD, JWT_SECRET, ...) se ponen en Dokploy → Environment.
# ─────────────────────────────────────────────────────────────

# 1) Compilar
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 2) Ejecutar
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=America/Bogota \
    DOCUMENTOS_DIR=/app/uploads
RUN mkdir -p /app/uploads
COPY --from=build /build/target/movify-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
