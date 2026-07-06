# 4. 로컬 개발 환경 및 설치 가이드

> 본 문서는 **Schale Queue** 개발에 필요한 로컬 환경을 처음부터 구성하는 절차를 안내한다.

## 4.1. 필수 설치 항목

아래 도구들은 프로젝트 개발의 전제 조건이다. 각 도구의 역할을 이해하고 설치한다.

| 도구 | 권장 버전 | 역할 |
| :--- | :--- | :--- |
| **JDK** | **21 (LTS)** | 백엔드 런타임 및 빌드. **Virtual Threads** 등 Java 21 신기능을 사용하므로 21 미만은 불가. (Temurin / Azul Zulu 권장) |
| **Node.js** | **20+** | React 프론트엔드 빌드 및 개발 서버 구동. |
| **Docker Desktop** | 최신 | MariaDB, Redis 등 로컬 인프라를 컨테이너로 구동. 환경 일관성의 핵심. |
| **Git** | 최신 | 버전 관리 및 Monorepo 형상 관리. |

### 설치 검증

설치 후 터미널에서 아래 명령으로 정상 설치를 확인한다.

```bash
java -version      # openjdk version "21.x.x" 확인
node -v            # v20.x.x 이상 확인
docker -v          # Docker version 확인
git --version      # git version 확인
```

## 4.2. AntiGravity IDE + Claude Code CLI 환경 세팅

본 프로젝트는 **AntiGravity IDE**의 내장 터미널에서 **Claude Code CLI**를 실행하여, AI 아키텍트 파트너와 협업하는 워크플로우를 기본으로 한다.

### 설정 절차

1. **AntiGravity IDE 실행** 후, 프로젝트 루트(`schale-queue/`)를 작업 디렉토리로 연다.
2. IDE 내장 **터미널(Terminal)**을 연다.
3. 터미널에서 **Claude Code CLI**를 실행한다.
4. CLI가 프로젝트 컨텍스트(특히 `docs/5_code_of_conduct.md`의 행동 강령)를 인식한 상태에서 개발을 진행한다.

> **TIP**: 셸 명령을 직접 실행해야 할 때(예: 대화형 로그인)는 프롬프트에 `!` 접두사를 붙여 입력하면, 명령이 현재 세션에서 실행되어 그 결과가 대화 컨텍스트에 직접 반영된다.

## 4.3. Docker Compose 기반 로컬 인프라 구동

MariaDB와 Redis는 로컬에 직접 설치하지 않고, **Docker Compose**로 일괄 구동한다. 이는 팀원 간/머신 간 환경 차이를 제거하고, 한 번의 명령으로 인프라를 켜고 끌 수 있게 한다.

### `docker-compose.yml` 예시 (Phase 1에서 확정)

```yaml
version: "3.8"

services:
  mariadb:
    image: mariadb:10.6
    container_name: schale-mariadb
    ports:
      - "3306:3306"
    environment:
      MARIADB_ROOT_PASSWORD: root
      MARIADB_DATABASE: schale_queue
      MARIADB_USER: schale
      MARIADB_PASSWORD: schale
    volumes:
      - schale-mariadb-data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    container_name: schale-redis
    ports:
      - "6379:6379"
    volumes:
      - schale-redis-data:/data

volumes:
  schale-mariadb-data:
  schale-redis-data:
```

### 인프라 구동 명령

```bash
# 인프라 백그라운드 구동
docker compose up -d

# 구동 상태 확인
docker compose ps

# 로그 확인
docker compose logs -f

# 인프라 종료 (데이터는 volume에 보존)
docker compose down
```

### 접속 정보 요약

| 서비스 | Host | Port | 비고 |
| :--- | :--- | :--- | :--- |
| **MariaDB** | localhost | 3306 | DB: `schale_queue` / user: `schale` |
| **Redis** | localhost | 6379 | 대기열 및 분산 락 |

## 4.4. 권장 실행 순서

```
1) Docker Desktop 실행
2) docker compose up -d        # 인프라 기동
3) backend 실행 (module-api)    # Spring Boot 애플리케이션
4) frontend 실행 (npm run dev)  # React 개발 서버
```

> 인프라(2)가 정상 기동되지 않은 상태에서 백엔드(3)를 실행하면 DB/Redis 연결 실패가 발생한다. 항상 인프라를 먼저 띄운다.

## 4.5. 풀스택 컨테이너 실행 (Phase 5 통합 배포)

개발 중엔 위(4.4)처럼 인프라만 컨테이너로 띄우고 앱은 `bootRun` 으로 실행하는 것이 빠르다. 반면 **전체 시스템을 한 번에** 띄워 데모하거나 통합 동작을 확인할 땐, `app` 프로파일로 api·worker 까지 컨테이너로 묶어 기동한다.

```bash
# 인프라만 (기본) — bootRun 개발 흐름. 앱은 호스트에서 localhost 로 접속
docker compose up -d

# 풀스택 — 인프라 + api + worker 컨테이너까지 (최초/소스 변경 시 --build)
docker compose --profile app up -d --build

# 상태/로그
docker compose --profile app ps
docker compose --profile app logs -f api worker

# 종료 (데이터 volume 보존)
docker compose --profile app down
```

### 동작 원리

- **프로파일 분리**: `api`/`worker` 서비스에 `profiles: ["app"]` 이 붙어 있어, `--profile app` 없이는 기동되지 않는다(기존 인프라-only 흐름 보존).
- **컨테이너 간 접속**: 컨테이너 앱은 `localhost` 가 아니라 **service 명**으로 인프라에 접속한다 — `mariadb:3306`, `redis:6379`, `kafka:29092`.
- **Kafka 듀얼 리스너**: 브로커가 호스트용(`localhost:9092`)과 내부용(`kafka:29092`) 두 리스너를 노출한다. 호스트의 `bootRun`/부하툴은 `9092`, 컨테이너는 `29092` 로 **같은 브로커**에 접속한다.
- **기동 순서**: api/worker 는 `depends_on: condition: service_healthy` 로 인프라 healthcheck 통과 후에야 뜬다(부팅 레이스 차단).
- **시크릿**: `DB_PASSWORD` 등은 이미지에 굽지 않고 `.env` 에서 런타임 주입한다(§5.3.2). 실행 전 `cp .env.example .env` 후 값을 채운다.

> ⚠️ **이미지 캐시**: 소스를 바꾼 뒤엔 `--build` 를 붙여야 새 코드가 반영된다. 안 붙이면 이전 이미지로 뜬다.

## 4.6. 프론트엔드 데모 실행 (Phase 5)

React 프론트(대기열 → 주문 → 결제 플로우)를 로컬에서 돌려보는 절차.

```bash
# 1) 백엔드 기동 — 풀스택(4.5) 권장. worker 가 떠 있어야 대기열이 소비되어 '입장'이 발생한다.
docker compose --profile app up -d --build

# 2) 데모 시드 적용 (⚠️ 기존 주문/재고/회원 데이터 초기화 — 로컬 전용)
docker compose exec -T mariadb sh -c 'mariadb -uschale -p"$MARIADB_PASSWORD" schale_queue' < load/seed/demo_seed.sql

# 3) 프론트 개발 서버
cd frontend && npm install && npm run dev   # http://localhost:5173
```

- **API 프록시**: 개발 서버가 `/api` 를 `http://localhost:8080` 으로 프록시한다(CORS 우회). API 포트를 바꿨다면 `VITE_API_PROXY_TARGET=http://localhost:8081 npm run dev`.
- **회원 식별**: 인증이 없으므로 첫 방문 시 1~1000 범위 무작위 회원 ID 를 발급해 localStorage 에 보관한다(데모 시드의 회원 범위와 일치). 헤더의 입력란에서 바꿀 수 있다 — 서로 다른 브라우저/시크릿 창으로 다중 대기 시연 가능.
- **SSE**: 대기 순번은 `X-Member-Id` 헤더가 필요해 EventSource 대신 fetch 스트리밍으로 구독한다.
