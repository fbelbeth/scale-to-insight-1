# Etapa de build: compila e empacota o serviço com Maven.
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copia metadados e código-fonte para gerar o jar.
COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

# Etapa de runtime: imagem menor apenas para execução do jar.
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar

# Porta HTTP do orders-service.
EXPOSE 5001

CMD ["java", "-jar", "app.jar"]
