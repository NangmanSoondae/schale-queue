# 11. 도메인 정책 (Domain Policy)

> ADR에 흩어져 있던 **암묵적 규칙**을 한 곳에 명문화한다. 각 정책은 고유 ID(P-Q*, P-S*, P-O*, P-P*)를 가지며, 기능 명세([`10_functional_spec.md`](./10_functional_spec.md))와 목표 지표([`9_nfr_and_slo.md`](./9_nfr_and_slo.md))가 이 ID를 참조한다.
>
> ⚠️ **상태**: 초안(Draft) — 정책 값(TTL·재시도 등)은 확정 전 제안값.

---

## 11.1. 대기열 정책 (Queue) — `P-Q*`

전방 Redis ZSET 기준(ADR-002 §2). 휘발 가능·실시간성 우선.

| ID | 정책 | 규칙(초안) | 근거 |
| :-- | :--- | :--- | :--- |
| **P-Q1** | 순번 공정성 | score = **진입 timestamp**, 오름차순 = 선착순(FIFO) | ADR-002 §3.1 / US-1 |
| **P-Q2** | 중복 진입 | 동일 회원 재진입 시 **신규 발급 없이 기존 순번 유지** (뒤로 밀지 않음) | UC-02 예외 |
| **P-Q3** | 입장 토큰 (**확정**) | dequeue 시 **Redis 키 + TTL**로 발급(`admission:{goodsId}:{memberId}`), **전역 TTL 5분**. 만료 시 주문 불가, 재진입 필요. **회원 인증과 별개**(입장 권한만 증명) | UC-04 |
| **P-Q4** | 입장률(throttle) | Worker dequeue rate = **DB 보호 상한 이하**(S3). UX와 DB 안전의 트레이드오프 | NFR S3 |
| **P-Q5** | 대기열 유실 복구 (**확정**) | **1차**: AOF + (운영 시)replica failover로 유실 최소화. **2차**(완전 유실 시): graceful degradation으로 재진입 안내 + **진입시각 서명 토큰**으로 순번 best-effort 복원. 초기 구현은 단순화하여 **맨 뒤 재진입** | ADR-002 §3.1·§3.4-1 |
| **P-Q6** | 이탈 처리 | 대기 중 연결 종료/타임아웃 → 일정 시간 후 큐에서 정리(좀비 방지) | 운영 |

> **P-Q3 보충**: 입장 토큰 TTL은 "큐 통과~주문 생성"까지의 입장 권한 수명이다. "주문 생성~결제 완료"의 재고 hold 수명은 별개 타이머(`P-O2` / `Payment.timeoutAt`)다. 입장 TTL은 전역 기본값(5분)으로 두고, 필요 시 상품별 확장 가능.
>
> **P-Q5 보충**: 큐 데이터는 "휘발돼도 재진입으로 복구 가능"(ADR-002 §3.1)한 성격이다. 진입시각을 서명 토큰에 담아두면 유실 후 재진입 시 그 timestamp를 score로 `ZADD`해 원래 순번을 근사 복원할 수 있다(개선 항목).

---

## 11.2. 재고 정책 (Stock) — `P-S*`

ADR-001 결정 1(Goods↔Stock 분리) 기준. 쓰기 경합 집중 영역.

**재고 모델 = 예약 기반 3-카운터(확정).** Stock 테이블이 가변 카운터 3개를 보유하고, 불변 기준값 `totalQuantity`를 둔다.

```
totalQuantity(불변) = availableQuantity + reservedQuantity + soldQuantity
```

| ID | 정책 | 규칙 | 근거 |
| :-- | :--- | :--- | :--- |
| **P-S1** | 오버셀 금지(최우선) | `availableQuantity >= 0` **항상 성립** + 합계 불변식 `total = available + reserved + sold` 유지. 초과 차감 절대 불가 | NFR S5/S6 (타협 불가) |
| **P-S2** | 차감 모델 (**확정: B안**) | **예약 기반 즉시 차감.** 주문 생성 시 `available--, reserved++`(soft hold, 원자적). 결제 확정 시 `reserved--, sold++`. 만료/실패/취소 시 `reserved--, available++`(해제) | UC-05/UC-06/UC-07 |
| **P-S3** | 락 전략 | 단일 DB = **JPA 비관적 락**(`FOR UPDATE`, Stock 단일 행)으로 다중 카운터 갱신을 한 임계구역에 묶음. Scale-out = **Redis 분산 락** | ADR-001 §2 / 아키텍처 §2.4 |
| **P-S4** | 재고 해제(복원) | 결제 실패/타임아웃/주문 취소 시 `reserved--, available++`로 예약 해제(P-O2와 연동) | UC-06/UC-07 |
| **P-S5** | 재고 표시 | 목록/상세의 재고는 **근사치 허용**(캐시), 확정 정합성은 주문 시점에만 보장 | ADR-001 §3 / UC-01 |

> **P-S2 확정됨 (B안)**: "예약 후 즉시 차감 + 시한부 hold(`Payment.timeoutAt`) + 만료 시 자동 해제" 모델로 확정. oversell은 주문 시점 `available > 0` 가드로 차단하고, 재고 묶임은 TTL 기반 해제(UC-07)로 방지한다. 단일 카운터(A안) 대비 **예약/판매 개수의 관측성**을 얻는 대신, 전이마다 두 카운터를 **원자적으로 함께 갱신**(P-S3 락 범위)해야 한다.
>
> ⚠️ **스키마 영향**: 본 결정은 ADR-001의 Stock 컬럼(`totalQuantity` / `remainQuantity`)을 **`totalQuantity` + `availableQuantity` + `reservedQuantity` + `soldQuantity`로 확장**한다. 형식 기록: [`12_adr_004_stock_reservation.md`](./12_adr_004_stock_reservation.md).

---

## 11.3. 주문 정책 (Order) — `P-O*`

ADR-001 결정 2(Order↔Payment 분리) 기준. 확정된 사실의 기록.

| ID | 정책 | 규칙(초안) | 근거 |
| :-- | :--- | :--- | :--- |
| **P-O1** | 주문 전제 | 유효한 **입장 토큰** 보유자만 주문 생성 가능 | UC-05 / P-Q3 |
| **P-O2** | 결제 타임아웃 → 취소 (**확정**) | 결제창은 **상품별 설정** `Goods.paymentTimeoutMinutes`(기본 10분, 허용 1~30분)로 결정. 주문 생성 시 `Payment.timeoutAt = now + 설정값`을 박고, 경과 시 주문 자동 취소 + **재고 해제**(P-S4) | UC-07 / ADR-001 §2 |
| **P-O3** | 1인 구매 한도 (**확정**) | **상품별 설정** `Goods.maxPurchasePerMember`(null=무제한, **기본 1**). 주문 생성 시 회원의 해당 상품 **활성(예약+확정) 수량**이 한도를 넘지 않게 검사. 동시성은 `(member_id, goods_id)` **유니크 제약(활성 1건 기준)**으로 차단 — 만료/취소 시 슬롯 반납 → 재시도 가능 | 선착순 공정성 |
| **P-O4** | 주문 상태 전이 | `CREATED → (결제) → PAID | CANCELED | EXPIRED`. 역전이 불가 | 상태기계 |
| **P-O5** | 취소/환불 (**확정**) | PAID 이후 취소/환불은 **상품 유형별 환불 정책**(§11.5 `P-R*`)에 따름. 재고 처리(쿨다운/복원) 동반 | ADR-006 / §11.5 |

> **P-O2/P-O3 보충 — 상품별 운영 설정**: 두 값은 모두 "변경 적은 설정 메타정보"이므로 **Stock(쓰기 경합 카운터)이 아니라 Goods**에 둔다(ADR-001 §2 정합). 상품 등록 화면에서 함께 입력하되 저장 위치는 Goods다. `paymentTimeoutMinutes`는 인기 드랍의 **예약 재고 회수 속도**를 운영자가 조절하는 손잡이 역할을 한다.

---

## 11.4. 결제 정책 (Payment) — `P-P*`

ADR-001 결정 2 + ADR-002 §3.4(무결성) 기준. 변동성·외부 의존 격리.

| ID | 정책 | 규칙(초안) | 근거 |
| :-- | :--- | :--- | :--- |
| **P-P1** | 상태 전이 | `PENDING → PAID | FAILED | EXPIRED`. timeoutAt 기준 만료 | ADR-001 §2 |
| **P-P2** | 멱등성 | 결제 요청/콜백에 **idempotency key** 적용 → 중복 승인·중복 차감 방어 | ADR-002 §3.4 |
| **P-P3** | 무유실 | 완료/실패 이벤트는 **Kafka로 무유실 발행**, 컨슈머 오프셋 재처리 | NFR S8 / ADR-002 §3.2 |
| **P-P4** | 재시도 (**확정**) | **3종 구분**: ① 일시 오류(timeout/5xx)=지수 백오프 자동 재시도(멱등키, 최대 N회) ② 거절=예약 TTL 내 사용자 재시도 ③ TTL 만료=EXPIRED→예약 해제→재큐잉. "일시 장애만 자동 재시도, 결정적 거절은 자동 재시도 안 함" | ADR-006 §2 / 9.3.3 |

---

## 11.5. 환불·취소 정책 (Refund) — `P-R*`

ADR-006 기준. 상품 유형(ADR-005)별로 분기. ⚠️ **정의 확정, 구현 후순위**(핵심 큐·동시성 이후).

| ID | 정책 | 규칙 | 근거 |
| :-- | :--- | :--- | :--- |
| **P-R1** | 결제 전 vs 후 구분 | 결제 전(PENDING) = 예약 해제(P-O2, 돈 무관). 결제 후(PAID) = 환불(실제 환급 + 재고 처리) | ADR-006 §1 |
| **P-R2** | 예매형 시간차 환불 | `eventDate`(도래일)까지 남은 기간으로 **부분환불 비율** 차등(표준: 15일전 100/7일전 50/3일전 20/이후 0%). 기준 시각=취소요청시각, **KST** | ADR-006 §2(a) |
| **P-R3** | 배송형 구매확정 환불 | 구매확정 **전** 변심 가능 / **후** 불가(하자 제외). `autoConfirmDays` **최소 7·기본 7**(청약철회권 보호) | ADR-006 §2(b) |
| **P-R4** | 환불정책 템플릿화 | `RefundPolicyTemplate` 재사용, `GoodsTicketDetail.refundPolicyTemplateId`로 참조 | ADR-006 §2 |
| **P-R5** | 부분환불 | 환불액 = 결제액 × 비율, 차액=취소수수료. 환불 이력 누적 | ADR-006 §3 |
| **P-R6** | 취소표 쿨다운 | 취소 재고는 **쿨다운 후 재오픈**(되팔이 방지, 예매형). 재고 4번째 상태(cooldown) 추가 → ADR-004 refine | ADR-006 §4 |
| **P-R7** | 보상환불 하이브리드 | 정합성 보상(후속 실패·중복결제 등)=**자동 Saga** / 분쟁성=**CS 수동** | ADR-006 §5 |
| **P-R8** | 환불 상태·멱등 | `PAID → REFUND_REQUESTED → REFUNDED | REFUND_FAILED`. **멱등키**로 중복 환불 방어 | ADR-006 §6 |

> **불변식 영향(P-R6)**: 쿨다운 도입 시 Stock 합계 불변식이 `total = available + reserved + sold + **cooldown**`으로 확장된다(예매형 활성, 배송형 비활성). 상세: [`14_adr_006_refund_cancellation_policy.md`](./14_adr_006_refund_cancellation_policy.md) §4.

---

## 11.6. 정책 간 상호작용 (핵심 시나리오)

**결제 타임아웃 → 재고 환원 → 다음 대기자 입장** (가장 중요한 회복 경로)

```
주문 생성(P-O1) ─ 재고 예약(P-S2: available→reserved) ─ Payment PENDING(timeoutAt)
        │
        ├─ 결제 성공(P-P1) ──▶ reserved→sold(P-S2) ─ Order PAID ─ Kafka 완료 이벤트(P-P3) ─ 알림(UC-08)
        │
        └─ timeoutAt 경과(P-O2) ──▶ Worker 정리(UC-07) ──▶ 재고 해제(P-S4: reserved→available)
                                                              └─▶ 입장 가속(P-Q4) ── 다음 대기자에게 기회
```

이 경로가 **"결제 실패 시 재고가 부당하게 묶이지 않는다"(US-4)**를 보장한다.

---

## 11.7. 확정 대기 항목

- [x] **P-S2** 차감 모델 → **B안(예약 기반 3-카운터) 확정** (2026-06-24)
- [x] **ADR-004** Stock 스키마 확장(`available`/`reserved`/`sold`) 형식 기록 (2026-06-24)
- [x] **P-O3** 1인 구매 한도 → `Goods.maxPurchasePerMember`(기본 1) + 유니크 제약(활성 1건) 확정 (2026-06-24)
- [x] **P-Q3** 입장 토큰 → Redis 키+TTL(전역 5분) 확정 (2026-06-24)
- [x] **P-O2** 결제 타임아웃 → `Goods.paymentTimeoutMinutes`(기본 10, 1~30) 상품별 설정 확정 (2026-06-24)
- [x] **P-Q5** 큐 유실 복구 → AOF+replica / 재진입+진입시각 서명 토큰 복원 확정 (2026-06-24)
- [x] **P-O5/P-P4 · P-R\*** 환불·취소·재시도 → ADR-005(유형 분리)·ADR-006(환불 정책) 확정, **구현 후순위** (2026-06-24)
- [ ] **기준 시나리오 수치·SLO 임계값** (NFR §9.2 — 기획 마무리 후 별도 확정)
- [ ] **P-O5/P-P4** 환불·재시도 상세 범위 (잔여)
- [ ] **기준 시나리오 수치·SLO 임계값** (NFR §9.2 — 기획 마무리 후 별도 확정)
