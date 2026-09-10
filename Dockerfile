# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
# Los tests corren en el pipeline, no en el build de la imagen: un build que
# necesita Testcontainers necesita un Docker adentro de Docker.
RUN mvn -B clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082 8083
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
