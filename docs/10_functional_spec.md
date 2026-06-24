# 10. 기능 명세 및 유스케이스

> "왜·어떻게"(기획·아키텍처)와 "얼마나 잘"(NFR/SLO) 사이의 빈칸 **"무엇을 만드는가"**를 채운다.
> 엔티티(Member·Goods·Stock·Order·OrderItem·Payment, ADR-001)와 큐 흐름(전방 ZSET / 후방 Kafka, ADR-002)에 정합한다.
>
> ⚠️ **상태**: 초안(Draft) — API 경로·필드는 구현 시 확정.

---

## 10.1. 액터(Actor)

| 액터 | 설명 |
| :--- | :--- |
| **게스트(Guest)** | 미인증 방문자. 상품 목록·상세 조회 가능. |
| **회원(Member)** | 인증된 사용자. 대기열 진입·주문·결제의 주체. |
| **관리자(Admin)** | 상품/재고 등록·운영·모니터링. (Phase 4 — MCP AI 어드민으로 확장) |
| **시스템 워커(Worker)** | `module-worker`. 대기열 소비·입장 토큰 발급·만료 정리·알림. |

---

## 10.2. 핵심 유저 스토리 (User Stories)

> 형식: *"~로서, ~을 위해, ~을 원한다."*

- **US-1 (대기 공정성)** — 회원으로서, 먼저 도착한 만큼 먼저 입장하기 위해, **선착순 순번**을 보장받기를 원한다.
- **US-2 (대기 가시성)** — 회원으로서, 무한정 기다림의 불안을 줄이기 위해, **내 순번과 예상 대기**를 실시간으로 보기를 원한다.
- **US-3 (정확한 재고)** — 회원으로서, 헛걸음을 피하기 위해, **재고가 있을 때만** 주문이 성사되기를 원한다(오버셀 없음).
- **US-4 (결제 안전)** — 회원으로서, 결제 실패 시 **재고가 부당하게 묶이지 않고** 다시 기회가 돌아오기를 원한다.
- **US-5 (운영 가시성)** — 관리자로서, 오픈 상황을 통제하기 위해, **대기 인원·입장률·재고 잔량**을 모니터링하기를 원한다.

---

## 10.3. 사용자 여정 (End-to-End Flow)

```
[상품 탐색] → [오픈 전 대기] → [대기열 진입] → [순번 대기/조회] → [입장(토큰 발급)]
   Guest          Member          ZADD           ZRANK            Worker dequeue
      │                                                              │
      ▼                                                              ▼
                                              [주문 생성 + 재고 차감] → [결제] → [완료/알림]
                                                JPA Lock(Stock)       Payment    Kafka(후방)
```

핵심 분기: **전방(대기열)** 은 휘발 가능·실시간성 우선, **후방(주문/결제)** 은 무유실·정합성 우선 (ADR-002).

---

## 10.4. 유스케이스 명세 (Use Cases)

### UC-01. 상품 목록·상세 조회
- **액터**: 게스트/회원
- **사전조건**: 없음
- **흐름**: 상품 목록 조회 → 상세 조회(Goods 메타 + Stock 잔량 표시)
- **비고**: Goods는 **캐시 가능**, Stock은 분리 조회(ADR-001 §3). 재고 표시는 근사치 허용, 확정은 주문 시점.
- **관련 API(초안)**: `GET /goods`, `GET /goods/{id}`

### UC-02. 대기열 진입 (Enqueue)
- **액터**: 회원
- **사전조건**: 인증됨, 상품 `openAt` 도래
- **기본 흐름**:
  1. 회원이 진입 요청 → Redis ZSET에 `ZADD`(score = 진입 timestamp, member = userId/token)
  2. 대기열 토큰(대기 식별자) 발급
- **예외**: `openAt` 이전 → 거부 / 이미 대기 중(중복 진입) → 기존 순번 반환([`11_domain_policy.md`](./11_domain_policy.md) 정책 P-Q2)
- **관련 API(초안)**: `POST /queue/{goodsId}/enter`

### UC-03. 순번 조회 (Rank) + 실시간 알림
- **액터**: 회원
- **흐름**: `ZRANK`로 현재 순번 반환 + **SSE**로 순번 변동·입장 시점 푸시(로드맵 Phase 3)
- **SLO**: 순번 조회 p99 < 50ms ([`9_nfr_and_slo.md`](./9_nfr_and_slo.md) S2)
- **관련 API(초안)**: `GET /queue/{goodsId}/rank`, `GET /queue/{goodsId}/subscribe` (SSE)

### UC-04. 입장 (Dequeue → 입장 토큰)
- **액터**: 시스템 워커
- **흐름**: Worker가 정해진 처리율(rate)만큼 ZSET 앞에서부터 꺼내 **입장 토큰** 발급(Redis 키 `admission:{goodsId}:{memberId}` + TTL) → 해당 회원만 주문 가능
- **정책**: 입장률 = DB 보호 상한(S3), 입장 토큰 = **Redis 키+TTL(전역 5분, P-Q3)**, 만료 시 재진입 필요
- **비고**: 입장 토큰 ≠ 회원 인증. 입장 권한만 증명.

### UC-05. 주문 생성 + 재고 차감 (임계구역)
- **액터**: 회원(입장 토큰 보유)
- **사전조건**: 유효한 입장 토큰
- **기본 흐름**:
  1. 입장 토큰 검증
  2. **1인 한도 검사**: 회원의 해당 상품 활성(예약+확정) 수량 < `Goods.maxPurchasePerMember` (P-O3, `(member,goods)` 유니크 제약)
  3. **재고 예약**(Stock 단일 행 `available--, reserved++`, JPA 비관적 락 / scale-out 시 Redis 분산 락 — P-S2)
  4. Order + OrderItem 생성(주문의 확정 사실), Payment 생성(status=PENDING, **timeoutAt = now + `Goods.paymentTimeoutMinutes`** — P-O2)
- **불변식**: `availableQuantity >= 0` 항상 성립, 오버셀 0건 (S5/S6, P-S1)
- **예외**: 재고 소진 → 주문 거부(품절 안내) / 토큰 만료 → 거부 / **한도 초과 → 거부**(P-O3)
- **관련 API(초안)**: `POST /orders`

### UC-06. 결제 (Payment)
- **액터**: 회원
- **흐름**: PG 연동 결제 요청 → 승인 시 Payment.status=PAID, Order 확정 → **후방 Kafka로 완료 이벤트 발행**
- **예외**: 실패/타임아웃 → Payment.status=FAILED/EXPIRED, **재고 복원**(P-O2), 재시도 안내
- **비고**: 결제 변동성은 Order에서 격리(ADR-001 §2). 멱등키로 중복 결제 방어(ADR-002 §3.4).
- **관련 API(초안)**: `POST /payments`, `POST /payments/{id}/confirm`

### UC-07. 만료 결제 정리 (Worker 배치)
- **액터**: 시스템 워커
- **흐름**: `Payment.timeoutAt` 경과 건 조회 → EXPIRED 처리 → **재고 복원** → (필요 시) 다음 대기자 입장 가속
- **근거**: ADR-001 §2 (timeoutAt 인덱스 기반 효율 조회)

### UC-08. 비동기 알림
- **액터**: 시스템 워커
- **흐름**: 주문 완료/실패 이벤트(Kafka) 구독 → 알림 발송
- **비고**: 발송은 **공용 알림 게이트웨이(notify-gateway)로 분리 예정**, 교체 전까지 현행 curl 유지(ADR-003, 행동강령 §5.3.4).

### UC-09. 관리자 운영·모니터링
- **액터**: 관리자
- **흐름**: 상품/재고 등록, 대기 인원·입장률·재고 잔량·주문 통계 모니터링
- **확장**: Phase 4에서 **MCP 기반 AI 어드민**(자연어 운영)으로 발전(로드맵 Phase 4).

---

## 10.5. API 표면 요약 (초안)

| 영역 | 메서드 · 경로(초안) | 유스케이스 |
| :--- | :--- | :--- |
| 상품 | `GET /goods`, `GET /goods/{id}` | UC-01 |
| 대기열 | `POST /queue/{goodsId}/enter` | UC-02 |
| 대기열 | `GET /queue/{goodsId}/rank` | UC-03 |
| 대기열 | `GET /queue/{goodsId}/subscribe` (SSE) | UC-03 |
| 주문 | `POST /orders` | UC-05 |
| 결제 | `POST /payments`, `POST /payments/{id}/confirm` | UC-06 |
| 관리자 | `POST /admin/goods`, `GET /admin/metrics` | UC-09 |

> 경로·요청/응답 스키마는 구현 시 OpenAPI로 확정한다. 본 표는 기능 범위 정렬용이다.

---

## 10.6. 범위 밖 (Out of Scope, 현 시점)
- 실제 PG사 상용 연동(모의/샌드박스로 대체 가능)
- 배송/물류, 쿠폰·프로모션 엔진, 정산 상세(이벤트 훅만 마련)
- 다국어/통화 다변화
