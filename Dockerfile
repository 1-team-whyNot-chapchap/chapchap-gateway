FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src/ src/
RUN ./gradlew --no-daemon --max-workers=2 bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app \
    && mkdir -p /app/logs && chown -R app:app /app
COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar
USER app
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
