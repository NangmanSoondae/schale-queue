# 2. 아키텍처 구조도 및 기술 스택

> 본 문서는 **Schale Queue**의 시스템 구조, 기술 선택의 근거, 그리고 핵심 문제 해결 전략을 정의한다.

## 2.1. 워크스페이스 구조 (Monorepo)

프로젝트는 백엔드, 프론트엔드, 문서를 하나의 저장소에서 통합 관리하는 **Monorepo** 전략을 채택한다. 이를 통해 도메인 모델과 API 명세의 일관성을 유지하고, 변경 이력을 단일 컨텍스트로 추적한다.

```
schale-queue/
├── backend/          # Spring Boot 멀티 모듈 (Java 21)
│   ├── module-core/    # 공통 도메인, Entity, Repository, 공유 설정
│   ├── module-api/     # 사용자 대면 REST API, 대기열 진입점, SSE
│   └── module-worker/  # 비동기 작업, 대기열 소비, 알림 발송
├── frontend/         # React 기반 클라이언트
├── docs/             # 기획 / 아키텍처 / 로드맵 / 행동 강령
└── docker-compose.yml  # 로컬 인프라 (MariaDB, Redis)
```

## 2.2. 백엔드 아키텍처 (Spring Boot 멀티 모듈)

관심사의 분리(Separation of Concerns)와 책임의 명확화를 위해 멀티 모듈로 설계한다. 각 모듈은 독립적인 빌드 단위이며, 의존성은 단방향으로만 흐른다.

```
        ┌──────────────────────────────────────────────────┐
        │                   module-api                     │
        │   (REST Controller, 대기열 진입, SSE Push)        │
        └───────────────────────┬──────────────────────────┘
                                │  depends on
        ┌───────────────────────▼──────────────────────────┐
        │                  module-core                     │
        │  (Domain Entity, Repository, Lock, 공통 Config)   │
        └───────────────────────▲──────────────────────────┘
                                │  depends on
        ┌───────────────────────┴──────────────────────────┐
        │                 module-worker                    │
        │   (대기열 소비 Consumer, 비동기 알림 Worker)       │
        └──────────────────────────────────────────────────┘
```

| 모듈 | 책임 | 핵심 키워드 |
| :--- | :--- | :--- |
| **module-core** | 모든 모듈이 공유하는 핵심 자산. 도메인 엔티티, JPA Repository, 락 추상화, 공통 예외 및 설정을 담당한다. | Domain, Entity, Repository |
| **module-api** | 외부 트래픽의 관문. 사용자 요청을 받아 대기열로 안내하고, 검증된 요청에 한해 비즈니스 로직을 수행한다. SSE를 통해 대기 상태를 실시간 전송한다. | REST, Queue Gate, SSE |
| **module-worker** | 사용자 응답과 분리된 백그라운드 처리. 대기열을 소비하여 입장 토큰을 발급하고, 주문 완료 알림 등 비동기 작업을 수행한다. | Consumer, Async, Notification |

## 2.3. 핵심 기술 스택

| 구분 | 기술 | 버전 / 비고 |
| :--- | :--- | :--- |
| **Language** | Java | **21 (LTS)** — Virtual Threads 활용 |
| **Framework** | Spring Boot | **3.4.x** |
| **Persistence** | Spring Data JPA | 도메인 영속성 및 Lock 제어 |
| **Database** | MariaDB | **10.6** — 주문/상품/재고의 원천 데이터 |
| **In-Memory** | Redis | 대기열(Sorted Set), 분산 락, 캐시 |
| **Frontend** | React | 사용자 클라이언트 |
| **Infra** | Docker | 로컬/배포 환경 컨테이너화 |

## 2.4. 핵심 문제 해결 전략

본 프로젝트가 풀고자 하는 세 가지 핵심 난제와 그 해결 전략은 다음과 같다.

### 전략 1. Virtual Threads를 활용한 고동시성 트래픽 처리

대기열과 예매 로직은 본질적으로 **I/O 바운드(DB 조회, Redis 통신, 네트워크 대기)** 작업이 지배적이다. Java 21의 **Virtual Threads(가상 스레드)**를 적극 활용하여, 적은 플랫폼 스레드로도 수만 개의 동시 요청을 블로킹 없이 처리한다. 전통적인 스레드 풀의 한계(스레드 고갈, 컨텍스트 스위칭 비용)를 극복하고, 처리량(throughput)을 극대화하는 것이 목표다.

### 전략 2. Redis Sorted Set을 활용한 트래픽 대기열 제어

순간 폭증하는 트래픽을 DB로 직접 흘려보내면 시스템은 붕괴한다. 모든 요청을 **Redis Sorted Set**으로 구성된 대기열에 먼저 진입시킨다.

- **Score**: 요청 도착 시각(timestamp) → **선착순(FIFO) 공정성** 보장
- **Member**: 사용자 식별자(userId / token)
- **동작**: Worker가 정해진 처리량(rate)만큼만 대기열에서 꺼내 입장시킴으로써, DB로 향하는 트래픽을 **평탄화(Traffic Shaping)**한다.

### 전략 3. JPA Lock + Redis 분산 락을 활용한 완벽한 동시성 제어

재고는 유한하며, 단 하나의 재고도 초과 판매(oversell)되어서는 안 된다. 상황에 맞는 이중 잠금 전략을 사용한다.

- **JPA Lock (비관적/낙관적 락)**: 단일 DB 인스턴스 내에서의 재고 차감 정합성을 트랜잭션 수준에서 보장한다.
- **Redis 분산 락**: 다중 인스턴스(scale-out) 환경에서, 동일 자원에 대한 임계 구역(critical section)을 클러스터 전역에서 직렬화한다.

> 이 세 전략의 유기적 결합이 **Schale Queue**의 심장이다. 트래픽은 **대기열**에서 평탄화되고, 처리량은 **가상 스레드**로 극대화되며, 정합성은 **이중 락**으로 사수된다.
