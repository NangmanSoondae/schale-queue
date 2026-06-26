# 🔥 부하 테스트 하니스 (Phase 3 ⑥)

k6 기반 부하 테스트로 [`docs/9_nfr_and_slo.md`](../docs/9_nfr_and_slo.md) §9.2 SLO를 실측 검증한다.
도구 선정: **k6**(thresholds로 SLO 합격/불합격 자동 판정) — §9.4 도구 확정.

## 측정 범위 (집중 1차)

| 셀 | 시나리오 | SLO |
|----|----------|-----|
| **B1** 기준부하 | `queue.js` — enqueue + position | S1 enqueue p99<100ms · S2 position p99<50ms |
| **B2** 동시성 극한 | `order.js` — 최소재고 10에 N명 동시 주문 | S4 주문 p99<200ms · **S5/S6 오버셀 0건** |
| **VT 비교** | 동일 시나리오를 VT on/off 로 2회 | 처리량·p99·스레드·메모리 (§9.4.1) |

> ⚠️ 단일 노드 로컬: 부하생성기와 SUT가 CPU를 공유하므로 **절대 수치보다 상대·비교값**(특히 VT 대비)이 신뢰 포인트. 오버셀(S5/S6)은 환경과 무관하게 통과해야 하는 불변식.

## 사전 준비

```bash
docker compose up -d                 # MariaDB + Redis (.env 자동 로드)
./gradlew :module-api:bootRun        # 앱 기동 (별도 터미널, .env 의 DB_* 주입 필요)
```

`.env` 가 필요하다(`cp .env.example .env` 후 값 작성, §5.3.2). k6는 도커 이미지(`grafana/k6`)로 실행하므로 로컬 설치 불필요.

## 실행

```bash
./load/run.sh seed-db                # 1) goods/stock/member 시드 (최초 1회)
./load/run.sh b1 100 30s             # 2) B1: VUS=100, 30초 — S1/S2 측정
./load/run.sh b2 200 1000            # 3) B2: VUS=200, 회원 1000 — S4 + 오버셀 검증
```

`b2` 는 내부적으로 `reset-orders → seed-tokens → order.js → oversell-check` 를 순차 실행한다.

## VT vs 플랫폼 스레드 비교 (§9.4.1)

앱을 두 모드로 각각 기동해 같은 시나리오를 돌리고 결과를 대조한다.

```bash
# (A) Virtual Threads ON (기본)
SPRING_THREADS_VIRTUAL_ENABLED=true  ./gradlew :module-api:bootRun
# (B) 플랫폼 스레드 (비교군)
SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew :module-api:bootRun
```

각 모드에서 `./load/run.sh b1` / `b2` 를 실행하고 처리량(iterations/s)·p99·스레드 수·메모리를 기록한다.
결과는 [`docs/load_test_report.md`](../docs/load_test_report.md) 에 실측 병기한다.

## 합격 기준

- §9.2 모든 대상 SLO 충족, 특히 **S5 오버셀 0건은 무조건 통과**.
- k6 thresholds 위반 시 exit code ≠ 0 → 자동 불합격 판정.
