# ADR-003. 알림 발송의 외부 게이트웨이 분리 (Notification Gateway Extraction)

> **ADR(Architecture Decision Record)**: 현재 schale-queue 내부에서 직접 처리하는 Discord 알림을, 여러 프로젝트가 공용으로 쓰는 **독립 알림 게이트웨이 서비스**로 분리하기로 한 결정과 그 점진 교체 전략을 기록한다.

- **상태(Status)**: Accepted (적용은 단계적 — **현행 curl 유지** → 게이트웨이 완성 시 **교체**)
- **일자**: 2026-06-22
- **관련 Phase**: Phase 4(비동기 알림 파이프라인 / EDA)와 연계 — [`3_roadmap.md`](./3_roadmap.md), [`7_adr_002_queue_architecture.md`](./7_adr_002_queue_architecture.md)
- **결정 대상**: §5.3.4 Discord 작업 알림의 발송 책임을 어디에 둘 것인가

---

## 1. Context (상황)

현재 알림은 **행동 강령 §5.3.4**에 정의된 대로, Discord 웹훅 URL을 **`curl`로 직접 호출**해 전송한다.

```
[schale-queue] ── curl ──▶ Discord 웹훅 URL (스크릿) ──▶ 채널
```

이 방식은 동작하지만 다음 한계가 있다.

1. **재사용 불가**: 발송 로직·웹훅 URL이 schale-queue에 종속되어, 다른 프로젝트가 같은 알림 인프라를 공유할 수 없다.
2. **시크릿 분산**: 웹훅 URL이 각 프로젝트 `.env`에 흩어진다. 채널이 늘면 시크릿 관리가 분산된다.
3. **셸 경유 취약성**: 인라인 `curl`은 멀티바이트(이모지) 페이로드가 셸 인자 처리 단계에서 깨질 수 있다. (회피책으로 UTF-8 파일 분리가 필요했음 — [`troubleshooting.md`](./troubleshooting.md) 참고)
4. **채널 확장성 부재**: Discord 외 Slack·이메일 등 채널 추가 시 호출부마다 손대야 한다.

> 요구가 "**여러 프로젝트가 공용으로 쓰는 알림 발송**"으로 확장되었으므로, 발송 책임을 schale-queue 밖의 **독립 서비스**로 빼는 것이 자연스럽다.

---

## 2. Decision (결정)

| 항목 | 결정 |
| :--- | :--- |
| **분리 형태** | **별도 레포** `notify-gateway` (독립 배포·독립 라이프사이클) |
| **소유권** | 게이트웨이의 **설계·구현은 별도 세션/레포가 소유**한다. 본 ADR은 schale-queue 관점(=언제·어떻게 갈아끼우나)만 다룬다. |
| **전환 전략** | **점진 교체(Strangler Fig)** — schale-queue는 현행 `curl`을 **잠정 유지**, 게이트웨이가 완성되면 호출 대상만 교체 |
| **schale-queue의 역할** | 게이트웨이의 **클라이언트(소비자)** 가 된다 |

- schale-queue는 **지금 코드/프로토콜을 바꾸지 않는다.** §5.3.4 curl 방식을 그대로 쓴다.
- 게이트웨이가 §4의 교체 트리거를 충족하면, §5.3.4의 **호출 대상 URL만** "Discord 웹훅" → "게이트웨이 API"로 바꾼다.

---

## 3. 기대 계약 (Integration Contract) — 교체 후 schale-queue가 의존할 표면

> ⚠️ 아래는 schale-queue가 **기대하는** 인터페이스다. **확정본은 `notify-gateway` 레포에서 정의**하며, 불일치 시 그쪽이 우선한다. (계약의 단일 진실 출처 = 게이트웨이 레포)

```http
POST /api/v1/notifications
Authorization: Bearer <PROJECT_API_KEY>
Content-Type: application/json

{
  "channel": "schale-ops",        // 논리 채널명 (게이트웨이가 실제 웹훅 URL로 매핑)
  "title":   "[Claude Code 알림]",
  "message": "작업 완료: ..."        // 이모지 포함 가능 (셸 우회 → 안전)
}
```

- **채널 매핑은 게이트웨이가 보유**한다 → 호출부(schale-queue)는 실제 Discord 웹훅 URL을 몰라도 된다. (시크릿 중앙화)
- 인증은 **프로젝트별 API Key**.

---

## 4. 교체 트리거 & 절차 (가장 중요)

**교체 트리거** — 아래 3가지가 모두 충족될 때 교체한다.

1. 게이트웨이의 `POST /api/v1/notifications` 가 안정적으로 동작 (재시도·429 백오프 포함)
2. `schale-ops` 등 schale-queue가 쓰는 **논리 채널 매핑**이 게이트웨이에 등록됨
3. **API Key 인증**이 적용됨

**교체 절차**

1. §5.3.4의 `curl` 대상 URL을 **Discord 웹훅 → 게이트웨이 API**로 변경 (+ `Authorization` 헤더 추가)
2. Discord 웹훅 URL 시크릿을 **게이트웨이로 이관**, schale-queue `.env`에서 `DISCORD_WEBHOOK_URL` 제거 (게이트웨이용 `NOTIFY_GATEWAY_API_KEY`로 대체)
3. `.env.example` 키 목록 동기화

**롤백** — 교체 후 문제가 생기면, §5.3.4의 **현행 curl(웹훅 직접 호출)로 즉시 복귀**할 수 있다. (현행 방식을 제거하지 않고 보존해 두는 것이 롤백 안전망이다.)

---

## 5. Consequences (근거 및 트레이드오프)

### 5.1. 장점

- **시크릿 중앙화**: 웹훅 URL을 게이트웨이만 보유 → §5.3.2 보안 원칙 강화.
- **멀티 채널/프로젝트 재사용**: 호출부 변경 없이 채널(Slack·이메일) 및 소비 프로젝트 확장.
- **셸 우회**: 앱 코드(HTTP 클라이언트)로 발송 → 이모지 깨짐 문제 소멸.
- **EDA 연계**: Phase 4의 Kafka 알림 컨슈머가 게이트웨이를 호출하는 형태로 자연스럽게 확장 가능.

### 5.2. 비용 / 리스크

| 항목 | 내용 |
| :--- | :--- |
| **운영 부담** | 별도 레포·배포·인증·모니터링이 추가된다. |
| **가용성(SPOF)** | 게이트웨이 장애 시 알림 전체가 멈춘다 → 미설정/장애 시 **graceful skip**(§5.3.4 원칙) 유지로 본 작업 흐름은 보호. |
| **계약 드리프트** | schale-queue의 §3 기대 계약과 게이트웨이 실제 API가 어긋날 수 있다 → 게이트웨이 레포를 SSOT로 두고 본 ADR을 갱신한다. |

### 5.3. 경계 (Out of Scope)

- 게이트웨이의 **내부 설계(저장소·재시도 큐·멀티테넌시 등)는 본 ADR 범위 밖**이며 `notify-gateway` 레포가 자기 ADR로 소유한다.
- 초기 세팅(룰·MCP·컨벤션) 핸드오프는 `docs/result/`의 핸드오프 문서로 별도 전달한다.

---

## 6. 결정 요약

| 결정 | 한 줄 요약 |
| :--- | :--- |
| 별도 레포 분리 | 공용 알림은 **독립 `notify-gateway`** 서비스로 |
| 점진 교체 | schale-queue는 **현행 curl 유지 → 완성 시 호출 대상만 교체** |
| 계약 SSOT | 기대 계약은 본 ADR, **확정 계약은 게이트웨이 레포** |
| 롤백 안전망 | 교체 실패 시 **현행 curl로 즉시 복귀** |
