# ============================================================================
#  frontend 컨테이너 (Phase 5) — 정적 빌드 + nginx 서빙 + /api 리버스 프록시
#  ----------------------------------------------------------------------------
#  · 빌드 스테이지: node:24-alpine — CI(frontend-build 잡)와 동일 메이저(Node 24)로
#    빌드 재현성을 맞춘다(§5.4.2). dist/ 만 산출하고 런타임엔 포함되지 않는다.
#  · 런타임 스테이지: nginx:1.27-alpine(stable 계열) — 정적 서빙 + /api 프록시.
#    브라우저는 same-origin 으로만 통신하므로 CORS 설정이 필요 없다(개발기의
#    Vite dev proxy 와 동일한 전략을 프로덕션 구성으로 옮긴 것).
# ============================================================================
# --platform=$BUILDPLATFORM: dist/ 는 정적 파일(arch 독립) — multi-arch 발행 시 네이티브 빌드 1회
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /app
# 의존성 레이어 분리: package*.json 만 먼저 복사해 소스 변경 시 npm ci 캐시를 보존한다.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
