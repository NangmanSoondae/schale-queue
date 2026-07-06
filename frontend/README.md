# 🎫 Schale Queue — Frontend

선착순 대기열 데모의 React 클라이언트. **상품 목록 → 상세 → 대기열(실시간 순번) → 입장 → 주문 → 결제 확정** 플로우를 구현한다.

- **스택**: React 19 · TypeScript · Vite · react-router-dom (UI 라이브러리 없이 경량 유지)
- **회원 식별**: 인증이 없는 시스템(X-Member-Id 규약)이라 첫 방문 시 1~1000 무작위 ID를 발급해 localStorage 보관 — 데모 시드(`load/seed/demo_seed.sql`)의 회원 범위와 정합. 헤더 입력란에서 변경 가능(시크릿 창으로 다중 대기 시연).
- **SSE 클라이언트**(`src/api/sse.ts`): 브라우저 EventSource는 커스텀 헤더(X-Member-Id)를 못 붙이므로 **fetch 스트리밍으로 text/event-stream을 직접 파싱**한다. `position` 이벤트로 순번 갱신, `admitted` 수신 시 주문 화면 자동 전환, 서버 emitter 타임아웃 시 자동 재구독.
- **진입 멱등(P-Q2)**: enqueue는 재진입 시 기존 순번을 유지하므로 대기열 화면 새로고침이 안전하다.

## 실행

```bash
# 개발 (HMR) — /api 는 vite.config.ts 프록시가 localhost:8080 으로 전달
npm install && npm run dev          # http://localhost:5173

# 프로덕션 구성 (nginx 컨테이너) — 루트에서
docker compose --profile app up -d --build   # http://localhost:3000
```

- API 포트를 바꿨다면: `VITE_API_PROXY_TARGET=http://localhost:8081 npm run dev`
- 컨테이너(`Dockerfile` + `nginx.conf`)는 same-origin `/api` 리버스 프록시 + **SSE 무버퍼링**(`proxy_buffering off`) 구성 — 상세는 [`../docs/4_local_setup.md`](../docs/4_local_setup.md) §4.6.

## 구조

```
src/
├── api/        # fetch 래퍼(ApiError), SSE 스트림 파서, 백엔드 DTO 타입
├── pages/      # GoodsList · GoodsDetail · Queue(SSE) · Order(주문+결제)
├── member.tsx  # X-Member-Id 컨텍스트 (localStorage)
└── format.ts   # KRW · UTC→로컬 시간 포맷터
```
