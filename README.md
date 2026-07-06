# 🎫 Schale Queue (샬레 큐)

> **순간 폭증하는 트래픽을 대기열로 평탄화하고, 한정 재고를 단 1개도 초과 판매하지 않는**
> 선착순 예매·커머스 백엔드. 설계 결정(ADR 8건)과 부하 실측(리포트 2건)으로 증명한다.

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 3.4" src="https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/Redis-Sorted%20Set-DC382D?logo=redis&logoColor=white">
  <img alt="Kafka" src="https://img.shields.io/badge/Kafka-EDA-231F20?logo=apachekafka&logoColor=white">
  <img alt="MariaDB" src="https://img.shields.io/badge/MariaDB-10.6-003545?logo=mariadb&logoColor=white">
  <img alt="React" src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black">
  <img alt="Docker" src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white">
</p>

---

## 📌 무엇을 증명하는 프로젝트인가

수강신청·티켓팅처럼 **오픈 순간 수천 명이 몰리는** 시나리오에서, 다음 세 가지를 코드와 실측으로 증명한다.

| 도전 과제 | 해법 | 실측 증거 |
| :--- | :--- | :--- |
| 🌊 **트래픽 폭주 흡수** | Redis ZSET 전방 대기열 + SSE 실시간 순번 + Worker 입장률 스로틀 | 1,000 VU 동시 버스트 **에러 0%** 흡수, 7,642 req/s · 입장률 ≈269/s 평탄화 |
| 🔒 **오버셀 0건** | JPA 비관적 락 + 예약 재고 모델(ADR-004) + 입장 토큰 게이트 | 재고 10 vs 200-way 동시 주문 → **성공 10 / 충돌 990 / 오버셀 0** |
| 📮 **이벤트 무유실** | 트랜잭셔널 아웃박스(ADR-007) + Kafka + 멱등 컨슈머 + DLT | 부하 중 **완료 주문 1,000 = PAID 1,000 = 정산 원장 1,000** (S8) |

## 🏗️ 아키텍처 한눈에

```
                     ┌──────────────────────────  전방 (실시간성 · Redis)  ─────────────────────────┐
 React (nginx :3000) │  진입 POST /queue/{g}/entries ──▶ ZSET 대기열 (Lua 원자 enqueue)             │
   /api 프록시 ──────▶│  순번 SSE  /queue/{g}/subscribe ◀─ 폴러 (상품별 벌크 MGET+ZRANK 파이프라인)  │
                     │  입장      Worker 가 배치 스로틀로 ZPOPMIN + 토큰 발급 (단일 Lua 원자 연산)   │
                     └──────────────────────────────────────────────────────────────────────────────┘
                                          │ 입장 토큰 (P-O1, 주문 시 1회 소진)
                     ┌──────────────────────────  후방 (무결성 · Kafka)  ───────────────────────────┐
                     │  주문(비관적 락, 예약 재고) → 결제 확정 ─▶ Outbox ─▶ Kafka order.completed   │
                     │                                          └▶ 정산 · 알림 컨슈머 (멱등, DLT)   │
                     └──────────────────────────────────────────────────────────────────────────────┘
```

- **전방=Redis, 후방=Kafka 하이브리드**: 순번 조회(ZRANK)가 생명인 구간과 무유실이 생명인 구간을 분리 배치 — 근거는 [ADR-002](docs/7_adr_002_queue_architecture.md).
- 멀티스텝 Redis 흐름(진입/입장)은 **Lua 스크립트 하나**로 원자화 — 부분 실패로 생기는 유실·고아 큐를 구조적으로 차단.
- Java 21 **Virtual Threads**: 동일 1,000 동시성을 플랫폼 스레드 **36개**로 처리(플랫폼 모델은 216개), 느린 블로킹 워크로드에선 처리량 **3.1×** 우위 실측.

## 📊 부하 실측 하이라이트

<details>
<summary><b>SLO 실측 결과 (요약 표)</b> — 상세: <a href="docs/load_test_report.md">Phase 3 리포트</a> · <a href="docs/load_test_report_phase5.md">Phase 5 통합 리포트</a></summary>

| 지표 | 목표 | 실측 | 판정 |
| :--- | :--- | :--- | :---: |
| 대기열 진입 p99 | < 100ms | 21.66ms (호스트) / 23.18ms (컨테이너) | ✅ |
| 순번 조회 p99 | < 50ms | 13.01ms / 13.99ms | ✅ |
| 오버셀 (200-way 경합) | 0건 | **0건** | ✅ |
| 스파이크(1,000 VU 즉시) 에러율 | < 0.1% | **0%** (107,032 요청) | ✅ |
| 입장 처리율 평탄화 | 200~500 TPS | ≈269 issues/s (설계값 250/s) | ✅ |
| Kafka 파이프라인 무유실 (S8) | 0 유실 | 완료 1,000 = PAID 1,000 = 정산 1,000 | ✅ |
| SSE 브로드캐스트 (2,000 구독) | 폴링 주기(1s) 내 | 직렬 1.49s → **벌크화 후 0.12s (12.4×)** | ✅ |

</details>

> 부하테스트는 성능 수치만 남긴 게 아니라 **잠복 결함 5건을 실측으로 적발·수정**했다
> (스키마 불일치, 워커 부팅 불가, SSE 브로드캐스트 포화, ERROR 로그 오탐 등 — [트러블슈팅 일지](docs/troubleshooting.md) 13건에 인과관계 5단계로 기록).

## 🚀 빠른 시작

```bash
cp .env.example .env          # 값 채우기 (DB 비밀번호 등 — 시크릿은 커밋 금지)
docker compose --profile app up -d --build    # 인프라 3종 + api + worker + frontend 풀스택
docker compose exec -T mariadb sh -c 'mariadb -uschale -p"$MARIADB_PASSWORD" schale_queue' < load/seed/demo_seed.sql
```

→ **http://localhost:3000** 에서 상품 선택 → 대기열 진입 → 실시간 순번(SSE) → 입장 → 주문 → 결제까지 체험.
개발 모드·상세 절차는 [`docs/4_local_setup.md`](docs/4_local_setup.md), 부하 재현은 [`load/README.md`](load/README.md).

## 📂 저장소 구조

```
schale-queue/
├── backend/
│   ├── module-core/     # 도메인·영속성 (대기열 Lua, 재고 락, 아웃박스)
│   ├── module-api/      # REST API + SSE (Virtual Threads)
│   └── module-worker/   # 대기열 소비·결제 만료·Kafka 컨슈머 (정산/알림)
├── frontend/            # React 19 + Vite + TS, nginx 컨테이너 (/api 프록시·SSE 무버퍼링)
├── load/                # k6 시나리오 + SSE 벤치 + 시드 (부하 재현 하네스)
└── docs/                # 기획 → 아키텍처 → ADR 8건 → SLO → 실측 리포트 → 트러블슈팅
```

## 📚 문서 인덱스 — 의사결정의 궤적

| 분류 | 문서 |
| :--- | :--- |
| 설계 | [아키텍처](docs/2_architecture.md) · [도메인 정책](docs/11_domain_policy.md) · [기능 명세](docs/10_functional_spec.md) · [NFR/SLO](docs/9_nfr_and_slo.md) |
| ADR | [엔티티 설계](docs/6_adr_001_entity_design.md) · [큐 하이브리드](docs/7_adr_002_queue_architecture.md) · [알림 외부화](docs/8_adr_003_notification_externalization.md) · [예약 재고](docs/12_adr_004_stock_reservation.md) · [컨텍스트 분리](docs/13_adr_005_bounded_context_split.md) · [환불 정책](docs/14_adr_006_refund_cancellation_policy.md) · [아웃박스](docs/15_adr_007_transactional_outbox.md) · [Flyway](docs/16_adr_008_schema_migration_flyway.md) |
| 실측 | [Phase 3 부하 리포트](docs/load_test_report.md) · [Phase 5 통합 리포트](docs/load_test_report_phase5.md) |
| 기록 | [트러블슈팅 일지 13건](docs/troubleshooting.md) — 발견→분석→고찰→해결→결과 5단계 포맷 |

---

<p align="center"><sub>Phase 1(뼈대) → 2(동시성) → 3(대기열) → 4(EDA) → 5(통합·배포) — <a href="docs/3_roadmap.md">로드맵</a> 전 구간 완주 · 2026.06 ~ 2026.07</sub></p>
