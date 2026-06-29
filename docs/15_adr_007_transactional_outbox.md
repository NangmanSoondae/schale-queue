# ADR-007. 무유실 이벤트 발행 — 트랜잭셔널 아웃박스(폴링 릴레이)

> **ADR(Architecture Decision Record)**: 결제 확정 → Kafka 이벤트 발행의 **무유실(at-least-once)** 보장 방식과, 아웃박스 릴레이 구현으로 **폴링 퍼블리셔**를 채택한 근거를 기록한다.

- **상태(Status)**: Accepted
- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (Kafka 이벤트 파이프라인 / EDA)
- **선행 결정**: [`7_adr_002_queue_architecture.md`](./7_adr_002_queue_architecture.md) §3.4 (무유실·exactly-once 관리 포인트)
- **대상 슬라이스**: S8 — 주문완료 이벤트 무유실 보강 + 컨슈머 멱등

---

## 1. Context (상황)

[UC-08 첫 슬라이스](https://github.com/NangmanSoondae/schale-queue/pull/15)에서 결제 확정 시 `OrderCompletedEvent` 를 Kafka 로 발행했다. 발행 신뢰성은 `@TransactionalEventListener(AFTER_COMMIT)` 브리지로, **DB 커밋 후** Kafka 로 전송한다.

문제는 **DB 와 Kafka 가 서로 다른 시스템이라 두 작업이 하나의 원자적 단위로 묶이지 않는다**는 것이다.

```
confirm() @Transactional ──커밋✅──▶ [AFTER_COMMIT 브리지] ──send──▶ Kafka
                          ▲ 이 틈에서 앱 크래시 / 네트워크 단절 / 전송 실패 시
                            주문은 COMPLETED 인데 이벤트는 영영 안 나감 (at-most-once)
```

즉 현재는 **"잘하면 한 번, 못하면 0번"(at-most-once)** 이라 이벤트가 유실될 수 있다. Phase 4 의 합격 질문은 *"무유실로 비동기 처리하는가?"* 이며, 이를 충족하려면 **"최소 한 번은 반드시 전달"(at-least-once)** 을 보장해야 한다.

---

## 2. Decision (결정)

### 2.1. 패턴 — 트랜잭셔널 아웃박스(Transactional Outbox)

발행할 이벤트를 **비즈니스 변경과 같은 DB 트랜잭션 안에서** `event_outbox` 테이블에 행으로 기록한다. 별도의 **릴레이(relay)** 가 그 행을 읽어 Kafka 로 실제 발행한 뒤 `SENT` 로 표시한다.

```
[confirm() — 단일 트랜잭션, 전부 같이 커밋]
  ├─ payment → PAID
  ├─ stock   → reserved→sold
  ├─ order   → COMPLETED
  └─ INSERT event_outbox(eventId, topic, payload(JSON), status=PENDING)   ← 같은 트랜잭션(원자적)

[릴레이 — 별도 주기]
  read PENDING → kafkaTemplate.send().get() → mark SENT
  (전송 실패 시 markSent 생략 → PENDING 유지 → 다음 틱 재시도 = 무유실)
```

핵심: 주문이 커밋됐으면 **"보낼 이벤트"도 무조건 DB 에 남는다.** 앱이 죽어도 행이 남아 재시도되므로 유실이 없다. 릴레이가 broker ack(`send().get()`) 후에만 `SENT` 로 바꾸므로, 미발행 행은 반드시 재시도된다(at-least-once).

### 2.2. 릴레이 구현 — **폴링 퍼블리셔(Polling Publisher)** 채택

`module-worker` 의 `@Scheduled` 작업이 `event_outbox` 의 `PENDING` 행을 주기적으로 읽어 Kafka 로 발행한다. (이미 워커에 동일한 `@Scheduled` 폴링 패턴이 있다 — 결제 만료 정리 `PaymentExpiryWorker`.)

### 2.3. 컨슈머 멱등(idempotency)

아웃박스는 **at-least-once** 라 같은 이벤트가 중복 발행/재전달될 수 있다. 컨슈머는 `processed_event(event_id, consumer_group)` 유니크 키로 이미 처리한 이벤트를 식별해 **재처리를 건너뛴다**(check → 처리 → 기록). `eventId` 는 발행 시점에 부여된 UUID 다.

---

## 3. Considered Options (대안 비교) — 릴레이를 무엇으로 구현할 것인가

> 아웃박스 테이블(무유실의 본체)은 두 방안 공통이다. 갈리는 것은 **"outbox 를 읽어 Kafka 로 옮기는 릴레이를 코드로 짜느냐, 인프라로 떼우느냐"** 뿐이다.

### 방안 A — 폴링 퍼블리셔 (✅ 채택)

워커의 스케줄러가 `SELECT ... WHERE status='PENDING'` → 발행 → `UPDATE status='SENT'`.

| | 평가 |
| :--- | :--- |
| **장점** | 추가 인프라 **0** (기존 DB+스케줄러+Kafka 만으로 완결). 코드가 직관적이라 이해·디버깅 쉬움. **이미 워커에 동일한 `@Scheduled` 폴링 패턴**(결제 만료 정리)이 있어 아키텍처 일관성 우수. |
| **단점** | 폴링 주기(기본 500ms)만큼의 **발행 지연**, 그리고 인덱스 조회의 **약한 주기적 DB 부하**. |

### 방안 B — Debezium CDC (Change Data Capture)

MariaDB binlog 를 Debezium 이 캡처해 Kafka Connect 로 스트리밍. 앱 코드에 릴레이가 없다.

| | 평가 |
| :--- | :--- |
| **장점** | 거의 실시간(저지연), 폴링 DB 부하 0, 대규모 시스템의 정석 패턴. |
| **단점** | **Kafka Connect + Debezium 커넥터 + binlog 설정**이라는 무거운 인프라 추가. 운영·디버깅 복잡도 급증. |

---

## 4. Rationale (왜 A 인가)

1. **병목이 아닌 곳을 최적화하지 않는다.** 트래픽 폭주는 **전방 대기열(Redis)** 이 받는다. 아웃박스가 다루는 것은 *결제까지 완료된 주문*뿐이고, 그 양은 재고와 워커 입장률(throttle)로 **이미 상한이 걸려 있다.** 인덱스 걸린 폴링 부하는 같은 트랜잭션의 재고/주문 쓰기 부하에 비해 무시할 수준 — CDC 로 없앨 만한 병목이 아니다.
2. **지연 절감의 실익이 없다.** 이 이벤트의 소비자는 **알림**이다. 알림이 폴링 주기(수백 ms)만큼 늦는 것은 문제가 되지 않는다. CDC 의 저지연을 쓸 곳이 없다.
3. **CDC 는 "부하 없음"이 아니라 "부하·복잡도의 이동"이다.** 폴링 부하를 없애는 대신 Kafka Connect JVM·binlog I/O·운영 복잡도·새로운 장애 지점을 얻는다. 게다가 현재 **Docker Desktop 불안정**(live e2e 보류 사유, troubleshooting 참조)에 무거운 커넥터를 더 얹는 것은 위험.
4. **YAGNI.** 단일 노드 포트폴리오 규모에서 CDC 는 over-engineering 이다. 학습·전시 목적의 CDC 도입은 매력적이나, 그것만으로 현시점의 복잡도 비용을 정당화하기 어렵다.

> **포기한 것**: 거의-실시간 발행과 폴링 부하 제거. 알림 용도에서 둘 다 체감 이득이 없어 수용한다.

---

## 5. Consequences (영향)

- **api 는 Kafka 의존에서 해방된다.** 발행 책임이 워커 릴레이로 이전되어, api 의 `OrderCompletedKafkaPublisher`(AFTER_COMMIT 브리지)·producer 설정·`spring-kafka` 의존을 제거한다. api 는 다시 순수 요청-응답 계층이 된다.
- **core 는 Kafka 비의존을 유지한다.** `PaymentService` 는 Kafka 가 아니라 `event_outbox` 테이블에 JSON 페이로드를 쓸 뿐이다(직렬화는 `ObjectMapper`).
- **컨슈머는 멱등**해진다(`processed_event` dedup) → Kafka 재전달·아웃박스 중복 발행에도 알림이 이중 발송되지 않는다.
- **운영 정리**: `SENT` 행은 누적되므로, 후속으로 보관기간 경과분 정리(배치 삭제/아카이브)를 둔다(아래 §6).

---

## 6. 향후 관리 포인트 / 확장 경로

1. **CDC 로의 이전 경로**: 규모가 커지거나 저지연이 요구되면 **방안 B(Debezium)** 로 이전한다. `event_outbox` 테이블 자체는 그대로 재사용 가능하며, 릴레이(폴링 스케줄러)만 Debezium 커넥터로 교체하면 된다 — **이 ADR 의 채택이 후속 이전을 막지 않는다.**
2. **SENT 행 정리**: 발행 완료 행의 주기적 정리(예: N일 경과분 삭제) 배치를 추가한다.
3. **재시도/독약 메시지**: 반복 실패하는 행에 대한 시도 횟수·백오프·DLQ 전략은 필요 시 별도로 다룬다(현재는 다음 틱 무한 재시도).
4. **컨슈머 멱등의 한계(정직한 명시)**: 멱등은 `check → notify → mark` 순서다. `notify` 성공 후 `mark` 커밋 실패라는 드문 창에서는 재전달 시 알림이 한 번 더 갈 수 있다(at-least-once + 드문 중복). 알림 도메인에서 드문 중복은 수용 가능하다(UC-08 슬라이스 전제와 동일).

---

## 7. 결정 요약

| 결정 | 한 줄 요약 |
| :--- | :--- |
| 패턴 = 트랜잭셔널 아웃박스 | 이벤트를 비즈니스 변경과 **같은 트랜잭션**에 행으로 박아 무유실 확보 |
| 릴레이 = 폴링 퍼블리셔(A) | 병목 아닌 곳에 무거운 인프라(CDC)를 얹지 않음 — 기존 `@Scheduled` 패턴 재사용 |
| 컨슈머 = 멱등 dedup | `processed_event(eventId, group)` 로 재전달/중복을 흡수 |
| CDC(B) = 보류 | 저지연·부하제거의 실익이 현 규모/용도에 없음 → 확장 경로만 남김 |
</invoke>
