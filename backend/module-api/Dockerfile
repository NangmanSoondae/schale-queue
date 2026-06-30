# ============================================================================
#  module-api 컨테이너 이미지 (멀티스테이지, Phase 5 통합 배포)
#  ----------------------------------------------------------------------------
#  · 빌드 컨텍스트 = 레포 루트(.). 멀티모듈이라 gradle 래퍼와 backend/ 전체가 필요하다.
#    compose: build.context=. / dockerfile=backend/module-api/Dockerfile
#  · 빌드/실행 모두 Java 21(툴체인과 일치). 래퍼 버전(8.11.1)을 그대로 사용한다.
#  · 시크릿(DB_PASSWORD 등)은 이미지에 굽지 않는다 — 런타임에 compose env 로 주입(§5.3.2).
# ============================================================================

# ---- build stage : gradle 래퍼로 bootJar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 1) 의존성 레이어 캐시: 래퍼·빌드스크립트만 먼저 복사 → 소스 변경 시 의존성 재다운로드 회피
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY backend/module-core/build.gradle backend/module-core/build.gradle
COPY backend/module-api/build.gradle  backend/module-api/build.gradle
COPY backend/module-worker/build.gradle backend/module-worker/build.gradle
RUN chmod +x gradlew && ./gradlew :module-api:dependencies --no-daemon || true

# 2) 소스 복사 후 실행 가능 jar 빌드(테스트는 CI 가 담당 → 이미지 빌드는 -x test 로 가볍게)
COPY backend ./backend
RUN ./gradlew :module-api:bootJar --no-daemon -x test

# ---- run stage : 슬림 JRE 로 실행 ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# 비루트 실행(최소 권한)
RUN useradd --system --uid 10001 schale
COPY --from=build /workspace/backend/module-api/build/libs/*.jar app.jar
USER schale
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
