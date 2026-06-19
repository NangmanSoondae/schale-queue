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
