# ADR-008. DB 스키마 관리 — `schema.sql` → Flyway 마이그레이션 전환

> **ADR(Architecture Decision Record)**: 데이터베이스 스키마를 관리하는 방식을 `schema.sql`(initdb 1회 실행)에서 **Flyway 버전 마이그레이션**으로 전환한 결정과 근거를 기록한다.

- **상태(Status)**: Accepted
- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (Kafka EDA) 이후 운영 토대 정비
- **계기**: [`troubleshooting.md` No.09](./troubleshooting.md) — 기존 볼륨에 신규 테이블 미생성(스키마 드리프트)

---

## 1. Context (상황)

지금까지 스키마는 `module-core/src/main/resources/schema.sql` 한 장으로 관리하고, MariaDB 컨테이너의 `docker-entrypoint-initdb.d` 마운트로 적용했다. 그런데:

- `initdb` 스크립트는 **데이터 볼륨이 빈 최초 1회 기동 시에만** 실행된다.
- S8(아웃박스)에서 `schema.sql` 에 `event_outbox`/`processed_event` 를 추가했지만, **기존 볼륨이 보존돼 있어 새 테이블이 실제 DB 에 생성되지 않았다**(No.09). 앱은 `ddl-auto=validate` 에서 부팅을 거부했고, 누락 DDL 을 수동으로 적용해 임시로 메웠다.

> 근본 문제: **"코드의 스키마"와 "실제 DB 의 스키마"가 어긋나며(drift), 그 차이를 증분으로 자동 반영하는 장치가 없다.** 앞으로 이벤트 테이블이 계속 늘 것이라 이 문제는 반복된다.

---

## 2. Decision (결정)

**Flyway(Community)** 를 도입해 스키마를 **버전 마이그레이션**으로 관리한다.

- 마이그레이션은 **순수 SQL** 파일로 `module-core/src/main/resources/db/migration/` 에 둔다.
  - `V1__initial_schema.sql` — 기존 7개 테이블(member·goods·stock·orders·purchase_slot·order_item·payment).
  - `V2__add_outbox_idempotency_tables.sql` — S8 의 `event_outbox`·`processed_event`.
- Flyway 가 DB 의 `flyway_schema_history` 로 적용 이력을 추적하고, **앱 부팅 시 미적용 마이그레이션만 순서대로 자동 실행**한다.
- `schema.sql` 과 docker-compose 의 `initdb` 마운트는 **제거**한다(단일 출처화 — 스키마는 Flyway 가 책임).
- Hibernate `ddl-auto=validate` 는 **유지**한다 — **DDL 생성은 Flyway, 엔티티-스키마 일치 검증은 Hibernate** 로 역할 분리.
- **baseline 전략**: `spring.flyway.baseline-on-migrate=true`. 비어 있지 않은 기존 DB(이력 테이블 없음)는 V1 로 baseline 처리되고 이후 버전(V2…)만 적용된다 → No.09 의 "기존 볼륨" 시나리오를 흡수.

---

## 3. Considered Options (대안 비교)

> 상세 비교는 작업 로그(Notion)에도 정리. 여기선 핵심만.

| 방안 | 평가 |
| :--- | :--- |
| **Flyway (✅ 채택)** | 마이그레이션 = **순수 SQL**. 이미 `schema.sql`(SQL)을 쓰던 우리에겐 이전 마찰이 거의 없다. Spring Boot 자동 통합 1급. 단순·명시적. |
| **Liquibase** | XML/YAML 추상 changeSet + **무료 자동 롤백**. DB 중립성·정교한 제어가 강점이나, MariaDB 단일·SQL 친숙·포트폴리오 규모인 우리에겐 그 강점이 잉여이고 학습/설정이 무겁다. |
| **현행 유지(`schema.sql`)** | 드리프트(No.09)를 못 푼다 — 기각. |
| **`ddl-auto=update`** | 자동 같지만 컬럼 삭제/변경을 예측 못 하게 처리해 **운영 금기**(데이터 유실 위험) — 기각. |

---

## 4. Rationale & Trade-offs

- **왜 Flyway**: 순수 SQL 기반이라 현 `schema.sql` 자산을 거의 그대로 옮긴다(낮은 마찰). MariaDB 단일·SQL 친숙 환경에서 Liquibase 의 추상화/중립성은 over-engineering. (CDC 보류·폴링 채택과 같은 YAGNI 논리 — ADR-007.)
- **자동 롤백 부재(정직한 명시)**: Flyway Community 는 `undo`(자동 롤백)가 **유료**다. 그러나 (1) 로컬은 `docker compose down -v` 로 재생성, (2) 실수는 **forward-fix**(되돌리는 새 마이그레이션 `V_n` 추가)로 처리한다 — 이는 업계 표준("roll forward")이며, 데이터가 있는 DB 의 자동 DDL 롤백은 본디 위험(예: 컬럼 추가 롤백 = 데이터 소실)해 무료 롤백이 있어도 실무에선 잘 안 쓴다. 우리 규모에선 자동 롤백 부재가 의사결정을 바꿀 요인이 아니다.
- **마이그레이션 불변 규율**: 한번 적용된 `V_n` 은 수정하지 않는다. 변경이 필요하면 새 `V_{n+1}` 을 추가한다(git 커밋을 다시 쓰지 않는 것과 동일).

---

## 5. Consequences (영향)

- `schema.sql` 삭제, docker-compose 의 initdb 마운트 제거. 스키마 출처가 `db/migration/V*.sql` 로 **일원화**.
- api·worker 부팅 시 Flyway 가 미적용 마이그레이션을 자동 적용(둘 다 같은 DB. Flyway 의 잠금으로 동시 실행도 안전 — 먼저 잡은 쪽만 적용, 나머지는 no-op).
- `@SpringBootTest` 기반 동시성 IT 는 컨텍스트 부팅 시 Flyway 가 스키마를 만들어 **initdb 마운트 의존이 사라진다**(자족적이 됨). `ddl-auto=none` 유지.
- 신규 테이블 추가는 이제 `V_{n}.sql` 한 장 추가로 끝 → No.09 재발 방지.

## 6. 향후 관리 포인트
1. 기존 운영 DB 에 처음 도입할 땐 baseline 버전을 실제 상태에 맞춰 1회 지정(우리 로컬은 `down -v` 로 깨끗이 재생성).
2. 반복 실패 마이그레이션/대용량 변경은 별도 운영 절차로 다룬다(현재 범위 밖).

---

## 7. 결정 요약

| 결정 | 한 줄 요약 |
| :--- | :--- |
| Flyway 채택 | 순수 SQL 버전 마이그레이션으로 스키마 드리프트(No.09) 근절 |
| schema.sql 제거 | 스키마 출처를 `db/migration/V*.sql` 로 일원화 |
| ddl-auto=validate 유지 | DDL=Flyway, 검증=Hibernate 역할 분리 |
| Liquibase 보류 | DB 중립·무료 롤백의 실익이 현 규모/용도에 없음(YAGNI) |
