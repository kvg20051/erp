FROM maven:3.9.11-eclipse-temurin-25 AS build
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src src
RUN mvn -B -ntp verify
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core curl && rm -rf /var/lib/apt/lists/* && groupadd -g 10001 nixu && useradd -u 10001 -g nixu -m nixu && mkdir -p /data/files && chown -R nixu:nixu /data
WORKDIR /app
COPY --from=build /src/target/nixu-0.1.0.jar /app/nixu.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/nixu.jar"]
