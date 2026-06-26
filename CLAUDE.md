# 🎫 CLAUDE.md — Schale Queue 프로젝트 컨텍스트

> 이 파일은 Claude Code가 이 디렉터리에서 실행될 때마다 자동 로드되는 **상시 컨텍스트**다.
> 세션을 새로 열면 가장 먼저 이 문서를 기준으로 행동한다.

---

## 1. 📌 프로젝트 개요

**Schale Queue (샬레 큐)** — 대용량 트래픽 제어에 특화된 **선착순 예매 및 커머스 백엔드** 서버.

순간 폭증하는 트래픽을 **대기열(Queue)**로 평탄화하고, 한정 재고를 **동시성 제어(Lock)**로 사수하는 것이 핵심 도전 과제다.

- **스택**: Java 21 · Spring Boot 3.4.x · Spring Data JPA · MariaDB 10.6 · Redis · React · Docker
- **구조**: Monorepo (`backend` / `frontend` / `docs`), Spring Boot 멀티 모듈 (`module-core` / `module-api` / `module-worker`)
- **핵심 전략**: Virtual Threads(고동시성) · Redis Sorted Set(대기열) · JPA Lock + Redis 분산 락(동시성)

---

## 2. 🧭 필수 컨텍스트 로드 지시 (가장 먼저 수행)

> **세션이 새로 열리면, 작업을 시작하기 전에 가장 먼저 아래 두 문서를 읽고 컨텍스트를 동기화할 것.**

| 순서 | 문서 | 목적 |
| :--: | :--- | :--- |
| 1️⃣ | [`docs/5_code_of_conduct.md`](docs/5_code_of_conduct.md) | **행동 강령** — 반드시 준수할 협업 규칙·프로토콜 |
| 2️⃣ | [`docs/3_roadmap.md`](docs/3_roadmap.md) | **로드맵** — 현재 Phase와 다음 목표 파악 |

추가 참고: `docs/2_architecture.md`(아키텍처), `docs/6_adr_001_entity_design.md`(엔티티 설계 결정), `docs/troubleshooting.md`(트러블슈팅 일지).

---

## 3. ⚡ 핵심 행동 지침 요약 (Reminders)

> 전체 규칙은 행동 강령에 있으며, 아래는 매 작업에서 놓치면 안 되는 **압축 리마인더**다.

- ✅ **[§5.3.1] 작업 완료 자동화** — '하나의 논리적 작업'이 끝나면 **스스로** 다음을 수행한다.
  - **Git Commit**: Conventional Commits 규칙(`feat`/`fix`/`docs`/`chore`/`refactor`/`test`) 준수.
  - **Notion Sync**: 작업 결과를 요약해 지정된 Notion 허브 페이지에 기록. (문제 발생 시 즉시 보고)
- 🔒 **[§5.3.2] 보안 절대 원칙** — DB 비밀번호·API Key·토큰·`.mcp.json` 등 민감 정보를 **절대 노출/커밋하지 않는다.** 커밋 전 `.gitignore` 동작을 `git check-ignore`로 확인한다. 실제 시크릿은 `.env`/`application-secret.yml`로 분리.
- 📝 **[§5.3.3] 브리핑 자동 저장** — 모든 최종 브리핑은 터미널 출력에 더해, **반드시 `docs/result/` 폴더에 `YYYYMMDD_HHMMSS_작업키워드.md`** 마크다운 파일로 저장한다. (이 폴더는 형상 관리 제외)
- 🔀 **[§5.3.5] PR 머지 = CI 게이트** — `main` 다이렉트 푸시 금지. 모든 작업은 브랜치 분기 → push → `gh pr create --fill` → **`gh pr checks --watch`(CI 그린 대기)** → `gh pr merge --squash --delete-branch`. **CI 레드면 머지 금지**(고쳐서 재푸시). 무검증 즉시 머지(구 패스트트랙)는 폐기됨(2026-06-26).

---

## 4. 🛠️ 주요 개발 명령어 (Build & Run)

> 로컬 인프라(MariaDB/Redis)를 먼저 띄운 뒤 애플리케이션을 실행한다. 상세는 [`docs/4_local_setup.md`](docs/4_local_setup.md) 참고.

<details>
<summary><b>① 로컬 인프라 (Docker Compose)</b></summary>

```bash
docker-compose up -d        # MariaDB + Redis 백그라운드 기동
docker-compose ps           # 컨테이너 상태 확인
docker-compose logs -f      # 로그 추적
docker-compose down         # 인프라 종료 (데이터 volume 보존)
```
</details>

<details>
<summary><b>② 백엔드 빌드 & 실행 (Gradle 멀티 모듈)</b></summary>

```bash
./gradlew build                         # 전체 모듈 빌드 + 테스트
./gradlew :module-core:test             # 특정 모듈 테스트만 실행
./gradlew :module-api:bootRun           # API 서버 실행 (실행 모듈)
./gradlew clean build                   # 클린 빌드
```

> ⚠️ 민감 값(`DB_PASSWORD` 등)은 `.env` 또는 환경변수로 주입한다. 저장소에는 플레이스홀더만 존재.
</details>

<details>
<summary><b>③ 프론트엔드 (React)</b></summary>

```bash
cd frontend
npm install
npm run dev
```
</details>

---

## 5. 📂 디렉터리 구조 (요약)

```
schale-queue/
├── CLAUDE.md            # ← 이 파일 (상시 컨텍스트)
├── build.gradle         # 루트 빌드 (공통 설정)
├── settings.gradle      # 모듈 구성
├── docker-compose.yml   # 로컬 인프라 (예정)
├── backend/
│   ├── module-core/     # 도메인 · 영속성 (라이브러리)
│   └── module-api/      # REST API (실행 모듈)
├── frontend/            # React 클라이언트 (예정)
└── docs/
    ├── 1~6_*.md         # 기획 · 아키텍처 · 로드맵 · 환경 · 행동강령 · ADR
    ├── troubleshooting.md
    └── result/          # 🔒 브리핑 저장소 (gitignore, §5.3.3)
```
