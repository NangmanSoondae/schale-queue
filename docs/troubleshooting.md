# 트러블슈팅 일지 (Troubleshooting Log)

> 본 파일은 **Schale Queue** 개발 과정에서 마주한 에러·버그와 그 해결 과정을 누적 기록한다.
> 작성 규칙과 5단계 포맷은 [`5_code_of_conduct.md`](./5_code_of_conduct.md) §5.2를 따른다.
>
> **원칙**: 기존 기록은 덮어쓰지 않는다. 항상 파일 하단에 일련번호(`No.NN`)를 부여하여 새 항목을 추가한다.

---

<!-- 아래에 트러블슈팅 항목을 누적합니다. 첫 항목은 [No.01]부터 시작합니다. -->

## [No.01] 시스템 Gradle 부재로 `gradle wrapper` 실행 불가

- **일자**: 2026-06-19
- **관련 Phase**: Phase 1 (빌드 환경 검증)
- **태그**: `#Gradle` `#빌드환경` `#Wrapper`

### 1) 발견 (Discovery)
`gradle wrapper` 명령으로 Wrapper를 생성하려 했으나, 시스템에 Gradle이 설치되어 있지 않았다.

```log
$ gradle -v
/usr/bin/bash: line 2: gradle: command not found
```

(JDK 21, Docker는 정상 설치되어 있었고 오직 Gradle CLI만 부재)

### 2) 분석 (Analysis)
근본 원인은 **Wrapper 부트스트랩의 닭-달걀 문제**다. `gradle wrapper` 태스크 자체가 시스템에 설치된 Gradle을 필요로 하는데, 그 Gradle이 없었다. 한편 Gradle Wrapper의 본질은 "프로젝트에 동봉된 `gradle-wrapper.jar`가 지정된 배포본을 내려받아 실행"하는 구조이므로, **시스템 Gradle 없이도 Wrapper 구성요소만 갖추면 빌드가 가능**하다.

### 3) 고찰 (Contemplation)
- **방안 A — 시스템에 Gradle 설치 (SDKMAN/수동)**: 정석이지만, Windows + Git Bash 환경에서 설치·PATH 구성 비용이 크고, 검증 목적과 무관한 전역 상태를 건드린다.
- **방안 B — 전체 배포본(zip ~130MB) 내려받아 `gradle wrapper` 1회 실행**: 확실하지만 무겁고, 결국 Wrapper 파일을 만드는 게 목적이므로 우회가 가능하다.
- **방안 C — Wrapper 구성요소를 공식 저장소(태그)에서 직접 부트스트랩(채택)**: `gradle-wrapper.jar` + `gradlew`/`gradlew.bat`를 공식 Gradle 저장소의 `v8.11.1` 태그에서 받고, `gradle-wrapper.properties`만 작성하면 된다. 가장 가볍고 결과물이 정석 Wrapper와 동일하다. 단, jar 무결성 확인이 필요하다(→ ZIP 시그니처 `PK` 검증으로 보완).

### 4) 해결 (Resolution)
방안 C를 채택했다.
- `raw.githubusercontent.com/gradle/gradle/v8.11.1` 에서 `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`를 다운로드.
- 받은 jar의 선두 2바이트가 `PK`(ZIP 시그니처)임을 확인하여 손상 여부 검증.
- `gradle-wrapper.properties`에 `distributionUrl=...gradle-8.11.1-bin.zip` 명시(§5.4.2 버전 근거와 일치).
- `chmod +x gradlew` 후 `./gradlew clean build` 실행.

### 5) 결과 (Result)
`BUILD SUCCESSFUL in 41s`. 멀티 모듈이 의도대로 분리되어 빌드됨을 확인했다(`module-core`는 `bootJar SKIPPED`로 라이브러리 jar, `module-api`는 `bootJar` 실행형). 이후 모든 빌드는 시스템 Gradle 의존 없이 `./gradlew`로 재현 가능해졌으며, 버전이 8.11.1로 고정되어 환경 간 일관성이 확보되었다.

## [No.02] Docker 데몬(Docker Desktop) 미기동으로 `docker compose up` 실패

- **일자**: 2026-06-19
- **관련 Phase**: Phase 1 (인프라 검증)
- **태그**: `#Docker` `#인프라`

### 1) 발견 (Discovery)
`docker -v`(CLI)는 동작했으나 `docker compose up -d`가 데몬 연결 실패로 종료되었다.

```log
unable to get image 'mariadb:10.6': failed to connect to the docker API at
npipe:////./pipe/dockerDesktopLinuxEngine; check if the path is correct and if
the daemon is running: open //./pipe/dockerDesktopLinuxEngine:
The system cannot find the file specified.
```

### 2) 분석 (Analysis)
Docker **CLI는 설치**되어 있으나 **Docker Desktop(데몬)이 실행 중이 아니었다.** CLI는 named pipe로 데몬과 통신하는데, 데몬이 떠 있지 않아 파이프 연결에 실패했다. (코드/설정 문제가 아닌 런타임 상태 문제)

### 3) 고찰 (Contemplation)
- **방안 A — 사용자에게 수동 실행 요청**: 안전하지만 검증 흐름이 중단된다.
- **방안 B — Docker Desktop 실행 파일을 직접 기동 후 데몬 준비를 폴링(채택)**: `Docker Desktop.exe`를 시작하고 `docker info`가 성공할 때까지 폴링하면 자동화 가능하다.

### 4) 해결 (Resolution)
`C:\Program Files\Docker\Docker\Docker Desktop.exe`를 기동한 뒤, `docker info`가 성공할 때까지 5초 간격으로 폴링했다. 데몬 준비 후 `docker compose up -d`를 재실행했다.

### 5) 결과 (Result)
데몬이 약 5초 만에 준비되어 MariaDB/Redis 컨테이너가 정상 기동(`healthy`)되었다. 향후 인프라 검증 전에는 **데몬 기동 여부를 선제 확인**하는 절차를 두기로 했다.
