# Etapa de build: compila e empacota o serviço Quarkus.
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copia metadados e código-fonte para gerar o pacote fast-jar.
COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

# Etapa de runtime: executa o quarkus-run com as libs empacotadas.
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/quarkus-app/ /app/quarkus-app/

# Porta HTTP do finance-service.
EXPOSE 5002

CMD ["java", "-jar", "/app/quarkus-app/quarkus-run.jar"]
