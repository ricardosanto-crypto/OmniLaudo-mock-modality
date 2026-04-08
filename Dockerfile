# Use a Maven image with cached dependencies
FROM maven:3.9.6-eclipse-temurin-17
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests -B -Dmaven.wagon.http.pool=false -Dmaven.wagon.httpconnectionManager.ttlSeconds=120

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=0 /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

