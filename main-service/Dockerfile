FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon \
    && app_jar="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$app_jar" \
    && cp "$app_jar" /workspace/app.jar

FROM eclipse-temurin:21-jre

ENV SERVER_PORT=8080 \
    TZ=Asia/Seoul

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 edupilot \
    && useradd --uid 10001 --gid edupilot --create-home \
        --home-dir /home/edupilot --shell /usr/sbin/nologin edupilot \
    && mkdir -p /app /var/lib/edupilot/storage \
    && chown -R edupilot:edupilot /app /var/lib/edupilot

WORKDIR /app

COPY --from=builder --chown=edupilot:edupilot /workspace/app.jar ./app.jar

USER edupilot:edupilot

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
