FROM registry.gitlab.com/tenpo/docker-images/openjdk:openjdk21-1.0-alpine-grafana
WORKDIR /app
COPY ./build/libs/document-generator-api-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 80
CMD ["java", "-jar", "app.jar"]