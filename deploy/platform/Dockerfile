# Atlas AI Platform image (local / review build). The production image recipe is decided at server cutover.
FROM node:24-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml -B -ntp -q dependency:go-offline
COPY backend backend
COPY --from=web /src/frontend/dist frontend/dist
RUN mvn -f backend/pom.xml -B -ntp -q -Plocal-web -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S atlas && adduser -S -G atlas -u 10001 atlas
WORKDIR /app
COPY --from=jar --chown=0:0 --chmod=0444 /src/backend/target/knowledge-assistant-0.1.0.jar /app/atlas.jar
USER 10001
EXPOSE 18081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/atlas.jar"]
