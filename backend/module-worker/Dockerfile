# ============================================================================
#  module-worker 컨테이너 이미지 (멀티스테이지, Phase 5 통합 배포)
#  ----------------------------------------------------------------------------
#  · 구조는 module-api/Dockerfile 과 동일(같은 멀티모듈 래퍼 빌드). 산출 jar 만 다르다.
#  · worker 는 웹 계층이 없는 백그라운드 모듈(web-application-type=none) — 포트 노출 없음.
#  · 시크릿은 런타임 compose env 로만 주입(§5.3.2).
# ============================================================================

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY backend/module-core/build.gradle  backend/module-core/build.gradle
COPY backend/module-api/build.gradle   backend/module-api/build.gradle
COPY backend/module-worker/build.gradle backend/module-worker/build.gradle
RUN chmod +x gradlew && ./gradlew :module-worker:dependencies --no-daemon || true

COPY backend ./backend
RUN ./gradlew :module-worker:bootJar --no-daemon -x test

# ---- run stage ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
RUN useradd --system --uid 10001 schale
COPY --from=build /workspace/backend/module-worker/build/libs/*.jar app.jar
USER schale
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
