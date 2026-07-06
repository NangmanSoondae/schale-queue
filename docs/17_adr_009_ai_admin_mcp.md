# ADR-009. AI 어드민 — MCP 서버(module-admin-mcp)로 자연어 운영 인터페이스 제공

> **ADR(Architecture Decision Record)**: 어드민(운영) 인터페이스의 형태 선정과 안전장치 설계를 기록한다.

- **일자**: 2026-07-06
- **상태**: Accepted
- **관련 Phase**: Phase 4 산출물(보류분) → v1 마지막 슬라이스로 이행
- **관련 문서**: [`3_roadmap.md`](./3_roadmap.md) Phase 4 · [`2_architecture.md`](./2_architecture.md)

---

## 1. 문제 (Context)

상품 등록·재고 조정·대기열/정산 모니터링 같은 **운영 작업**의 인터페이스가 없다. 현재는 SQL 직접 실행(시드 스크립트)이나 curl 로 때우고 있다. 전통적 해법은 어드민 웹 UI 지만, 이 프로젝트의 기획 원안(Phase 4)은 **"MCP 를 활용한 AI 어드민 제어 환경"** — 자연어로 운영하는 실험이다.

## 2. 결정 (Decision)

**어드민 웹 UI 를 만들지 않고, 새 실행 모듈 `module-admin-mcp` 를 MCP(Model Context Protocol) 서버로 제공한다.** Claude Desktop / Claude Code 등 MCP 클라이언트가 이 서버의 툴을 호출해 자연어 운영을 수행한다.

```
Claude Desktop/Code ──(MCP · stdio JSON-RPC)──▶ module-admin-mcp ──▶ module-core 서비스 / JDBC ──▶ MariaDB · Redis
```

| 선택 | 채택 값 | 근거 |
| :--- | :--- | :--- |
| 프로토콜 구현 | **Spring AI 1.0.0 GA** (`spring-ai-starter-mcp-server`) | 프로토콜(JSON-RPC·핸드셰이크·툴 스키마)은 SDK 가 처리 — 우리는 툴 정의만 작성. 1.0.0 은 Boot 3.4.x 기반 GA(§5.4.2 근거: 첫 정식 안정판, Boot 버전 정합) |
| 전송 | **stdio** | Claude Desktop/Code 의 표준 로컬 연결. 네트워크 포트·인증 불요 — 신뢰 경계가 '로컬 프로세스 실행 권한'과 일치. 원격 필요 시 SSE 전환은 v2 |
| 모듈 형태 | 별도 실행 모듈(module-admin-mcp) | api/worker 와 동일한 경계 원칙 — core 서비스 재사용, 사용자 대면 경로와 분리(어드민 장애가 판매 경로에 무영향) |
| 집계 조회 | JdbcTemplate(읽기 전용) | 리포트성 집계는 JPA 엔티티/락 경로를 우회하는 게 안전·단순. 도메인 규칙이 필요한 조회(Goods 등)만 core 서비스 사용 |

## 3. 안전장치 (Safety Design)

1. **1차 슬라이스 = 읽기 전용 툴만.** 쓰기 툴(상품 등록·재고 조정)은 2차에서 추가하되:
   - `schale.admin.write-enabled=false` 가 기본 — 명시적으로 켜야 쓰기 툴이 등록된다.
   - 파괴적 변경(재고 감소 등)은 확인 파라미터(`confirm=true`)를 요구해 AI 의 단독 실수를 한 단계 차단.
2. **stdout 오염 금지.** stdio 전송에서 stdout 은 JSON-RPC 채널이다 — 배너/콘솔 로깅을 전부 끄고 로그는 파일로만 남긴다(설정 위반 시 클라이언트 연결이 조용히 깨지는 부류의 결함).
3. **스키마 소유권 불변.** admin 모듈은 Flyway 를 실행하지 않는다(`flyway.enabled=false`) — 마이그레이션 소유권은 기존 실행 모듈에 있고, admin 은 스키마를 검증만 한다(ddl-auto validate).
4. **신뢰 경계 명시.** 로컬 stdio = 서버를 실행할 수 있는 사람이 곧 운영자. 원격 노출(v2 SSE) 시에는 API 키 인증을 필수로 재설계한다.

## 4. 포기한 대안 (Alternatives)

- **어드민 웹 UI(React 페이지 추가)**: 화면·폼·권한 작업량이 크고, "AI 시대 운영 인터페이스" 실험이라는 기획 의도와 다름. 판단형 운영(여러 툴 조합·해석)은 UI 로는 애초에 불가.
- **REST 프록시형 MCP(별도 Node 서버)**: 어드민 전용 REST API 를 module-api 에 먼저 뚫어야 해 이중 작업. 사용자 대면 API 에 어드민 표면을 섞는 것도 경계 위반.
- **HTTP/SSE 전송 우선**: 로컬 데모(v1)엔 인증 설계가 선행돼야 하는 SSE 보다 stdio 가 안전·단순. v2 배포 트랙에서 재평가.

## 5. 툴 카탈로그

**1차 (읽기 전용, 본 ADR 시점 구현)**

| 툴 | 설명 |
| :--- | :--- |
| `list_goods` | 전체 상품 + 판매 상태(서버 UTC 판정)·한도·결제창 |
| `stock_status` | 상품별 재고 3-카운터(available/reserved/sold) + 합계 불변식 확인 |
| `queue_status` | 활성 대기열별 대기 인원 + 워커 소비 설정(배치/주기) |
| `order_summary` | 최근 N시간 주문 상태별 건수·금액 |
| `settlement_summary` | 최근 N시간 정산 원장 집계(gross/fee/net) |

**2차 (쓰기, write-enabled 게이트)**: `create_goods` · `adjust_stock(confirm)` · `update_goods_open_at`

## 6. 결과 (Consequences)

- (+) 기획 원안의 Phase 4 산출물 완성. 어드민 UI 없이 운영 데모 가능 — "대기열 상태 어때?" 수준의 자연어 운영.
- (+) core 서비스 재사용이라 어드민 로직 중복 없음. 모듈 경계로 판매 경로와 격리.
- (−) MCP 클라이언트(Claude)가 있어야 쓸 수 있다 — 어드민의 가용성이 외부 도구에 결합(실험적 선택임을 인지).
- (−) stdio 특성상 다중 운영자 동시 접속 없음(로컬 1:1) — 필요 시 v2 SSE.
