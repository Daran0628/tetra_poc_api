# syntax=docker/dockerfile:1
# Issuance Service 컨테이너 이미지 (진행 문서 M10)
#   빌드:  docker build -t tetra/issuance-service:local issuance-service
#   실행:  PoC 폴더에서  docker compose --profile app up -d --wait
# 환경은 TETRA_ENV 로 고른다 → values/values-${TETRA_ENV}.yml (로컬 컨테이너는 docker)

# --- 1단계: 빌드 (JDK 21 + Gradle Wrapper) ---------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 의존성 내려받기를 소스와 분리해 캐시가 오래 살게 한다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 Testcontainers(Docker 필요)라 이미지 빌드에서는 돌리지 않는다 — 로컬·CI에서 ./gradlew test
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

# --- 2단계: 실행 (JRE 21) -------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/app.jar app.jar
# application.yml 이 file:./values/values-${TETRA_ENV}.yml 을 읽으므로 실행 위치 기준으로 둔다
COPY values values
USER app

ENV TETRA_ENV=docker \
    TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# /actuator/health 가 UP 이어야 정상 (db·redis 포함). busybox wget 사용
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
