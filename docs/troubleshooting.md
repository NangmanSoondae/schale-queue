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

## [No.03] Testcontainers가 Docker Engine을 찾지 못함 (docker-java ↔ 최신 Docker API 비호환)

- **일자**: 2026-06-19
- **관련 Phase**: Phase 2 (재고 동시성 통합 테스트)
- **태그**: `#Testcontainers` `#Docker` `#테스트` `#동시성`

### 1) 발견 (Discovery)
재고 동시성 검증을 위해 Testcontainers(MariaDB)를 사용한 `StockConcurrencyTest` 실행 시, 컨테이너 기동 단계에서 초기화가 실패했다.

```log
java.lang.IllegalStateException: Could not find a valid Docker environment.
Testcontainers version: 1.20.6
EnvironmentAndSystemPropertyClientProviderStrategy: failed with exception
  BadRequestException (Status 400: {"ID":"", ... ,
  "Labels":["com.docker.desktop.address=npipe://\\.\pipe\docker_cli"], ...})
NpipeSocketClientProviderStrategy: failed with exception BadRequestException (Status 400 ...)
```

CLI(`docker compose up`, `docker info`)는 정상 동작하는데 Testcontainers만 실패했다.

### 2) 분석 (Analysis)
- `docker context ls` 결과 활성 컨텍스트는 `desktop-linux`(`npipe:////./pipe/dockerDesktopLinuxEngine`)였다.
- `DOCKER_HOST`를 해당 파이프로 지정하자 `EnvironmentAndSystemPropertyClientProviderStrategy`가 그 값을 사용했으나(=환경변수 전파는 정상), **여전히 `Status 400` + 빈 Info(라벨만 `docker_cli`)**가 반환되었다.
- 근본 원인은 **Testcontainers 1.20.6에 묶인 docker-java 클라이언트가 매우 최신 Docker Engine(Server 29.5.3)의 API와 비호환**이라는 점이다. 엔진은 CLI에는 정상 응답하지만, docker-java가 협상하는 API 버전/경로에는 400을 반환했다. (Spring Boot 3.4 BOM이 고정한 TC 버전이 최신 엔진을 따라가지 못함)

### 3) 고찰 (Contemplation)
- **방안 A — Testcontainers/docker-java 버전 강제 상향**: BOM 핀을 깨고 최신 TC로 올린다. 최신 Docker 29.x 지원 보장이 불확실하고, BOM 일관성을 해친다(§5.4.2와 충돌 소지).
- **방안 B — Docker Desktop의 TCP 데몬 노출(2375) 사용**: 보안 토글을 켜야 하고(비TLS 노출) 운영 표준과 어긋난다.
- **방안 C — 이미 `docker-compose`로 띄운 실제 MariaDB에 직접 접속(채택)**: 검증의 본질(=실제 InnoDB에서 비관적 락으로 oversell=0 증명)을 그대로 충족하면서, 불안정한 TC 부트스트랩 의존을 제거한다. 단, 테스트가 인프라 기동을 전제하므로 `RUN_DB_IT=true` 환경변수로 게이팅하여 일반 빌드를 깨지 않는다.

### 4) 해결 (Resolution)
방안 C를 채택했다.
- `module-core/build.gradle`에서 Testcontainers 의존성을 제거.
- 테스트 `application.yml`을 `docker-compose` MariaDB(`localhost:3306`) 접속 + `ddl-auto=none`(스키마는 `schema.sql`이 이미 생성)으로 구성. 접속 정보는 환경변수 주입(기본값은 비밀 아닌 플레이스홀더).
- `StockConcurrencyTest`에 `@EnabledIfEnvironmentVariable(named="RUN_DB_IT", matches="true")`를 부여해 통합 테스트를 명시적으로 게이팅.

### 5) 결과 (Result)
`RUN_DB_IT=true`로 실행하여 **`StockConcurrencyTest` 1건 통과(잔여 0, 성공 100, 실패 0 — 초과 판매 0건)**, 단위 테스트 `StockServiceTest` 2건도 통과했다. 비관적 락이 실제 MariaDB에서 의도대로 동작함을 증명했다. 또한 게이팅 덕분에 인프라 없는 일반 `./gradlew build`는 영향받지 않는다. (Testcontainers는 추후 엔진/TC 버전 정합이 맞을 때 CI 이식성 목적으로 재도입 검토)

---

## [No.04] Discord 웹훅 알림이 `400 (code 50109, invalid JSON)`으로 거부됨

- **일자**: 2026-06-24
- **관련 Phase**: Phase 3 (대기열 슬라이스 ①+② 완료 알림, §5.3.4)
- **태그**: `#Discord` `#인코딩` `#GitBash` `#curl` `#Windows`

### 1) 발견 (Discovery)
슬라이스 완료 알림을 §5.3.4의 `curl` 인라인 `-d '{...}'` 방식으로 Discord 웹훅에 전송하자 거부됐다.

```log
응답 코드: 400
본문: {"message": "The request body contains invalid JSON.", "code": 50109}
```

웹훅 URL 자체는 정상이었다(형식 점검 결과 `https://discord.com/api/webhooks/...` 일치). 콘텐츠에는 한글·이모지(🤖)·원문자(①②)가 포함돼 있었다.

### 2) 분석 (Analysis)
- 1차 시도는 URL 끝 CRLF(`\r`) 의심으로 `tr -d '\r'` 후 재시도했으나 동일하게 `400 50109`였다 → URL 문제 아님.
- 근본 원인은 **Windows Git Bash 셸이 커맨드라인 인라인 문자열의 멀티바이트 UTF-8(한글/이모지/원문자)을 깨뜨려 curl에 전달**한 것이다. 깨진 바이트열이 JSON 문자열을 무효화해 Discord가 `50109`(invalid JSON)를 반환했다. 즉 JSON 구조가 아니라 **인코딩**이 문제.

### 3) 고찰 (Contemplation)
- **방안 A — 인라인 문자열 유지 + 이스케이프 보강**: 셸 인코딩 자체가 원인이라 근본 해결이 아니며 환경마다 불안정.
- **방안 B — ASCII로만 콘텐츠 작성**: 메시지 가독성(한글 상태 문구)을 희생. 임시방편.
- **방안 C — 페이로드를 UTF-8 파일로 저장 후 `--data-binary @file` 전송(채택)**: 셸의 문자열 처리 경로를 우회해 파일 바이트를 그대로 전송하므로 인코딩 손상이 없다. 한글·이모지 유지 가능.

### 4) 해결 (Resolution)
방안 C를 채택했다.
- 알림 JSON을 UTF-8 파일(스크래치패드)에 작성(Write 도구로 생성 → BOM 없는 UTF-8 보장).
- 전송: `curl -H "Content-Type: application/json; charset=utf-8" --data-binary "@payload.json" "$WEBHOOK"`.
- 웹훅 값은 `.env`에서만 로드하고 터미널에 출력하지 않음(§5.3.2 준수).

### 5) 결과 (Result)
**응답 코드 `204`(성공)** 로 알림이 정상 전송됐다. 한글·이모지가 깨지지 않고 채널에 표시됐다. 교훈: Git Bash에서 멀티바이트 본문을 외부 프로세스에 넘길 때는 인라인 문자열 대신 **UTF-8 파일 + `--data-binary @file`** 을 기본 패턴으로 삼는다. (이 원칙은 향후 `notify-gateway` 전환 시에도 페이로드 전달에 동일 적용 가능)

---

## [No.05] 앱 첫 실제 부팅에서 `Schema-validation: wrong column type` 으로 기동 실패 (goods.description)

- **일자**: 2026-06-26
- **관련 Phase**: Phase 3 (슬라이스 ⑥ 부하테스트 준비 — module-api 첫 실 DB 부팅)
- **태그**: `#JPA` `#Hibernate` `#스키마검증` `#MariaDB` `#ddl-auto`

### 1) 발견 (Discovery)
부하테스트를 위해 `module-api` 를 실제 MariaDB 에 처음으로 `bootRun` 하자 컨텍스트 초기화 중 기동이 실패했다.

```log
org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation:
 wrong column type encountered in column [description] in table [goods];
 found [text (Types#LONGVARCHAR)], but expecting [tinytext (Types#CLOB)]
```

그간 통합테스트는 `LettuceConnectionFactory`/`docker-compose` 직접 접속으로 **JPA 컨텍스트를 띄우지 않거나**(대기열 IT) DataSource 만 썼기에, 엔티티↔스키마 정합이 전체 부팅 경로에서 검증된 적이 없어 잠복해 있었다.

### 2) 분석 (Analysis)
- `Goods.description` 은 `@Lob @Column String` 으로 매핑돼 있었다. Hibernate(MariaDB 방언)는 `@Lob`+String 을 **CLOB → `tinytext`** 로 기대한다.
- 그러나 `schema.sql` 의 실제 컬럼은 `TEXT` 다. `spring.jpa.hibernate.ddl-auto=validate` 가 이 타입 불일치를 잡아 부팅을 중단시켰다.
- 즉 **DDL(TEXT)과 엔티티 매핑(@Lob→tinytext)의 의도가 어긋난** 것이며, validate 가 의도대로 "조용한 불일치"를 부팅 실패로 드러낸 정상 동작이다.

### 3) 고찰 (Contemplation)
- **방안 A — `ddl-auto` 를 none 으로 완화**: 검증을 끄면 증상은 사라지나 근본 불일치를 덮어 더 위험(런타임에 엉뚱한 타입). 기각.
- **방안 B — schema.sql 을 `TINYTEXT` 로 변경**: 엔티티(@Lob)에 맞추는 방향이나, 설명 컬럼을 255바이트로 좁혀 도메인 의도(긴 설명)를 훼손. 기각.
- **방안 C — 엔티티를 DDL(TEXT)에 맞춤(채택)**: `@Lob` 제거 후 `@Column(columnDefinition = "TEXT")` 로 명시. 의도된 스키마(TEXT)와 엔티티가 일치하고 validate 통과. 가장 정합적.

### 4) 해결 (Resolution)
방안 C를 채택했다.
- `Goods.description`: `@Lob @Column` → `@Column(columnDefinition = "TEXT")`, `jakarta.persistence.Lob` import 제거.
- 부하테스트 슬라이스(⑥) 진행 중 발견된 블로커라 같은 작업에서 함께 수정했다.

### 5) 결과 (Result)
`module-api` 가 실제 MariaDB 에 정상 부팅됐고 부하테스트를 진행할 수 있게 됐다. 교훈: **통합테스트가 전체 부팅 경로(JPA 컨텍스트 + validate)를 거치지 않으면 엔티티↔DDL 불일치가 잠복**한다. 첫 실 DB 부팅은 그 자체로 의미 있는 검증 지점이며, 부하테스트 준비가 이를 앞당겨 드러냈다.


---

## [No.06] module-worker 가 부팅조차 못 함 (① JPA 결합 빈 스캔 ② VT 데몬 스레드로 즉시 종료)

- **일자**: 2026-06-26
- **관련 Phase**: Phase 3 (부하 측정 ⑦ — 입장률 S3 측정 위해 워커 첫 실기동)
- **태그**: `#Spring` `#ComponentScan` `#VirtualThreads` `#daemon` `#Worker`

### 1) 발견 (Discovery)
B3 입장률(S3) 측정을 위해 `module-worker` 를 처음으로 실제 기동하자 두 단계로 실패했다.

```log
# ① 컨텍스트 생성 실패
UnsatisfiedDependencyException: ... 'orderService' → 'stockService' →
 No qualifying bean of type 'StockRepository' available
# ②(①수정 후) 시작하자마자 종료
Started SchaleQueueWorkerApplication in 0.949 seconds ... BUILD SUCCESSFUL  (JVM 종료)
```

그간 워커는 `QueueConsumerTest`(Mockito 단위)로만 검증됐고 **전체 컨텍스트로 부팅된 적이 없어** 두 결함이 잠복해 있었다.

### 2) 분석 (Analysis)
- **①** 워커는 Redis 전용이라 DataSource/JPA 자동구성을 제외(ADR-002 §3.3)하는데, `@ComponentScan("com.schale.queue")` 가 core 의 JPA 결합 `@Service`(OrderService→StockService→StockRepository)까지 스캔해 빈 생성을 시도 → JPA 리포지토리 빈이 없어 컨텍스트가 깨졌다.
- **②** 워커에 `spring.threads.virtual.enabled=true` 가 켜져 있어 `@Scheduled` 스케줄러가 **가상 스레드(데몬)** 를 쓴다. 웹 서버(비데몬)도 없으니 `main` 반환 후 **데몬 스레드만 남아 JVM 이 즉시 종료**됐다. yml 주석의 "비데몬 스레드가 프로세스를 유지"는 VT 도입으로 깨진 가정이었다.

### 3) 고찰 (Contemplation)
- ① **JPA 를 켠다**(워커에 DB 부여) → "Redis 전용 워커" 설계(부팅 가속·장애 격리) 포기. 기각. **스캔에서 JPA 도메인 제외**(채택) → 설계 의도와 정합, 결제/정산 워커 슬라이스에서 JPA 와 함께 되살리면 됨.
- ② **VT 끄기** → 프로젝트 전략 훼손. **scheduler 스레드를 비데몬으로** → 커스터마이징 부담. **`spring.main.keep-alive=true`**(채택, Boot 3.2+) → 비웹 앱을 살리는 표준 수단, 한 줄.

### 4) 해결 (Resolution)
- ①: `@ComponentScan` 에 REGEX 제외 추가 — `core.domain.(order|stock|payment|goods|member)` 패키지 스캔 제외.
- ②: worker `application.yml` 에 `spring.main.keep-alive: true` 추가.

### 5) 결과 (Result)
워커가 정상 부팅·상주하며 대기열을 소비했고, **입장률 ≈ 269 issues/s**(batch 50 / 200ms = 250/s 설계값, 목표 200~500 TPS 내)를 실측했다. 교훈: **단위 테스트만 있고 전체 컨텍스트 부팅을 한 번도 안 거친 모듈은 통합 결함이 잠복**한다. 부하 측정이 그 첫 부팅을 강제해 두 결함을 동시에 드러냈다(스키마 No.05 와 같은 교훈).

## [No.07] Kafka 도입 후 Docker Desktop 간헐 크래시 → 자원 상한으로 안정화

- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (Kafka EDA)
- **태그**: `#Docker` `#Kafka` `#인프라` `#안정성`

### 1) 발견 (Discovery)
UC-08(첫 Kafka 슬라이스)·S8(아웃박스) 두 차례에 걸쳐 **실 브로커 대상 live e2e 가 "Docker Desktop 반복 크래시"로 보류**됐다. 다만 본 진단 시점엔 스택이 6시간째 정상(`healthy`, kafka 재시작 0회)이라, 재현되는 상태가 아니라 **간헐적 VM 불안정**임을 확인했다.

진단 결과(자원 구성):

```
KAFKA_HEAP_OPTS=            ← Kafka JVM 힙 상한 미지정(기본 ~1G, RSS 931MB)
mem_limit 미설정 (kafka/mariadb/redis 모두) ← 각 컨테이너가 Docker VM 의 15.2GiB 전체를 볼 수 있음
healthcheck: kafka-topics.sh / interval 10s ← 10초마다 JVM 풀 기동(수백 MB 순간 스파이크)
```

### 2) 분석 (Analysis)
근본 원인은 **컨테이너 자원의 무제한 노출**이다. (1) JVM 기반 Kafka 브로커는 힙·페이지캐시가 늘 수 있는데 per-container `mem_limit` 가 없어, 어느 컨테이너든 부풀면 **Docker Desktop 의 WSL2 VM 메모리를 굶겨 VM 자체가 죽는다**(컨테이너 재시작이 아니라 데몬/VM 다운이라 "반복 크래시"로 체감됨). (2) 헬스체크가 10초마다 `kafka-topics.sh`로 **JVM 을 풀 기동**해 주기적 메모리 압박을 더한다. 즉 코드가 아니라 **인프라 자원 격리 부재** 문제.

### 3) 고찰 (Contemplation)
- **방안 A — 브로커를 Redpanda 로 교체**: Kafka API 호환·경량(JVM 없음)이라 근본적이나, 인프라 스택 변경이라 범위가 크고 "Kafka 유지" 방침과 어긋남.
- **방안 B — Docker Desktop 자체 길들이기(엔진 다운그레이드/WSL2 튜닝)**: 머신-로컬·토끼굴 위험, repo 로 공유 불가.
- **방안 C — compose 에 자원 상한·경량 헬스체크 부여(채택)**: repo 에 커밋되어 모든 환경에 적용되고, 크래시 벡터(무제한 메모리)를 직접 차단한다. 변경 최소·코드 무영향.

### 4) 해결 (Resolution)
방안 C 채택 — `docker-compose.yml`:
- **Kafka 힙 고정**: `KAFKA_HEAP_OPTS: "-Xmx768m -Xms256m"`.
- **per-container `mem_limit`**: kafka `1536m` / mariadb `768m` / redis `256m` (총 ~2.5g 상한 → 호스트 여유 확보).
- **헬스체크 경량화**: kafka `interval 10s→30s` + `start_period 40s`(반복 JVM 기동 1/3 로 감소).
- (호스트 권고, 비커밋) Windows `~/.wslconfig` 로 WSL2 메모리를 호스트보다 낮게(예: 8GB) 캡해 OS 여유를 남길 것.

### 5) 결과 (Result)
하드닝 후 `docker compose up -d` 로 재생성 → **Kafka 10초 만에 `healthy`, 재시작 0**, 힙 상한(`-Xmx768m`) 반영 확인, 셋 다 `healthy`. 이어 **브로커 라운드트립 스모크**(앱 릴레이와 동일한 raw JSON 을 `order.completed` 에 produce → 동일 페이로드 consume)가 통과해, 그동안 미검증이던 **브로커 레벨 Kafka 경로가 처음으로 실증**됐다. 무제한 메모리 크래시 벡터를 제거했다(간헐 현상이라 "완전 근절"은 장기 관찰 필요 — 정직하게 명시). 앱 전체 e2e(api+worker 동시 기동→실 알림)는 별도 후속.

## [No.08] 워커 부팅 실패 — ObjectMapper 빈 부재 (spring-web 없는 모듈의 Jackson 자동구성 한계)

- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (Kafka EDA, 아웃박스 e2e)
- **태그**: `#SpringBoot` `#Jackson` `#멀티모듈` `#통합결함`

### 1) 발견 (Discovery)
아웃박스 슬라이스(S8) 후 **첫 앱 전체 e2e** 에서 api 는 정상 기동했으나 **worker 가 부팅 실패**했다.

```log
APPLICATION FAILED TO START
Parameter 6 of constructor in com.schale.queue.core.domain.payment.PaymentService
required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found.
```

### 2) 분석 (Analysis)
S8 에서 `PaymentService`(core)에 아웃박스 직렬화용 `ObjectMapper` 의존을 추가했다. **api 는 정상**인데 worker 만 실패한 이유: Spring Boot 의 `JacksonAutoConfiguration` 이 `ObjectMapper` 빈을 만들 때 **spring-web 의 `Jackson2ObjectMapperBuilder` 에 의존**한다. api 는 spring-web 이 있어 자동 빈이 생기지만, **워커는 웹 계층이 없어(spring-web 부재) 자동 ObjectMapper 빈이 만들어지지 않는다.** jackson-databind 가 클래스패스에 있어도 빌더가 없으면 빈이 없다.

> 근본 교훈(반복): **단위 테스트는 ObjectMapper 를 목으로 대체**해 이 배선 결함을 못 잡았다. 전체 컨텍스트를 한 번도 부팅 안 한 모듈은 통합 결함이 잠복한다(No.05/No.06 과 동일). e2e 가 첫 부팅을 강제해 드러냈다.

### 3) 고찰 (Contemplation)
- **방안 A — 워커에 spring-web 추가**: 자동구성은 살아나지만 비웹 모듈에 웹 스택을 끌어들여 책임 경계가 흐려짐.
- **방안 B — core 에 `@ConditionalOnMissingBean` ObjectMapper 정의**: 사용자 @Configuration 은 자동구성보다 먼저 평가돼, api 에서 Boot 의 풀 ObjectMapper 대신 core 것이 선점될 위험(REST 직렬화 거동 변경 소지).
- **방안 C — 워커 모듈에 ObjectMapper 빈 명시(채택)**: 결함이 있는 워커에만 한정 제공. api 는 Boot 빈 그대로, core 는 무변경. 부수효과 없음.

### 4) 해결 (Resolution)
`module-worker` 에 `config/JacksonConfig` 추가 — `JsonMapper.builder().addModule(new JavaTimeModule()).disable(WRITE_DATES_AS_TIMESTAMPS).build()` 로 `ObjectMapper` 빈 제공(이벤트 `LocalDateTime` 직렬화 대비 jsr310 등록, 컨슈머 JsonDeserializer 와 동일 규약).

### 5) 결과 (Result)
워커 정상 부팅 → confirm 호출 시 **outbox 적재→릴레이 발행(SENT)→Kafka→컨슈머 수신→processed_event 멱등 기록**까지 한 사이클 실증. 알림은 게이트웨이 404 → **웹훅 폴백 204(실 Discord 발송)** 로 §5.3.4 폴백도 라이브 확인. 같은 eventId 재발행 시 **"중복 이벤트 무시"** 로 멱등도 라이브 확인. 미검증이던 EDA 체인 전체가 처음으로 끝까지 흘렀다.

## [No.09] 기존 볼륨에 신규 테이블 미생성 — `schema.sql` 은 최초 1회만 실행 (스키마 드리프트)

- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (Kafka EDA, 아웃박스 e2e)
- **태그**: `#Docker` `#MariaDB` `#스키마` `#마이그레이션`

### 1) 발견 (Discovery)
e2e 시드 중 `event_outbox` 에 접근하자 실패. `SHOW TABLES` 결과 **`event_outbox`/`processed_event` 가 실제 DB 에 없었다**(엔티티·`schema.sql` 에는 있음). 이 상태로 앱을 띄우면 `ddl-auto=validate` 가 스키마 불일치로 부팅을 막는다.

### 2) 분석 (Analysis)
MariaDB 컨테이너의 `docker-entrypoint-initdb.d/schema.sql` 은 **데이터 디렉터리가 빈 최초 기동 시에만 실행**된다. S8 에서 `schema.sql` 에 두 테이블을 추가했지만, **기존 `schale-mariadb-data` 볼륨이 보존**돼 있어 init 스크립트가 다시 돌지 않았다 → 스키마 드리프트. 마이그레이션 도구(Flyway 등) 부재로 증분 DDL 이 자동 반영되지 않는 구조적 한계.

### 3) 고찰 (Contemplation)
- **방안 A — `docker compose down -v` 로 볼륨 폐기 후 재기동**: 전체 `schema.sql` 재실행되나 **기존 데이터 전부 소실**(파괴적).
- **방안 B — 누락 DDL 만 수동 적용(채택, 임시)**: `CREATE TABLE IF NOT EXISTS` 로 두 테이블만 비파괴 생성. 데이터 보존.
- **방안 C — Flyway/Liquibase 도입(후속 권고)**: 증분 마이그레이션으로 드리프트 근절. 별도 작업으로 분리.

### 4) 해결 (Resolution)
방안 B 로 `event_outbox`/`processed_event` 를 running DB 에 `CREATE TABLE IF NOT EXISTS` 로 직접 생성(정의는 `schema.sql` 과 동일).

### 5) 결과 (Result)
두 테이블 생성 후 앱 검증(validate) 통과·e2e 진행. **임시방편임을 명시한다** — 근본 해결은 마이그레이션 도구 도입(방안 C)이며, 그전까지 신규 테이블 추가 시엔 (a) 로컬 볼륨 재생성 또는 (b) 수동 DDL 적용이 필요하다(후속 후보로 기록). (→ ADR-008 Flyway 도입으로 근본 해결됨)

## [No.10] 결제 만료가 발동하지 않음 — 생성/검사 타임존 불일치(시스템존 vs UTC clock)

- **일자**: 2026-06-29
- **관련 Phase**: Phase 4 (주문취소 이벤트 e2e 중 발견)
- **태그**: `#타임존` `#Clock` `#결제만료` `#통합결함`

### 1) 발견 (Discovery)
주문취소 이벤트(결제 만료 → 취소) e2e 에서, 만료 임박 결제를 시드하고 워커를 띄웠으나 **만료 정리가 전혀 발동하지 않았다**(`결제 만료 정리` 로그 없음 = 대상 0건). DB 의 `timeout_at` 은 분명 과거였다.

```
앱 now(만료 검사) ≈ 09:08 (UTC)   vs   timeout_at = 18:04 (KST 로 저장)
→ 18:04 < 09:08 = false → 만료 대상 아님
```

### 2) 분석 (Analysis)
시간 출처가 두 곳에서 **달랐다**. `OrderService` 의 결제 생성은 `LocalDateTime.now()`(**시스템 기본 존 = KST**)로 `timeout_at` 을 설정했는데, 만료 검사 `PaymentExpiryWorker → PaymentService.findDueOrderIds(LocalDateTime.now(clock))` 는 `clock = systemUTC()`(**UTC**)를 썼다. MariaDB 의 naive `DATETIME` 에 KST 로 적힌 값과 UTC 로 계산한 now 를 비교하니 ~9시간 어긋나, **5분짜리 타임아웃이 사실상 ~9시간 늦게** 발동하는 셈이었다(실서비스에선 치명적). 프로젝트 의도는 `Clock` 빈(UTC)으로 시간을 통일하는 것인데 `OrderService` 만 이 규약을 벗어나 있었다.

> 단위 테스트가 못 잡은 이유: `OrderServiceTest` 도 `LocalDateTime.now()`(시스템존)로 기대값을 만들어 서비스와 **같은 편향**을 공유했고(로컬 KST 에선 우연히 일치), 만료 검사와의 교차 검증은 없었다. e2e(실 워커 부팅)가 비로소 드러냈다(No.05/06/08 과 같은 교훈).

### 3) 고찰 (Contemplation)
- **방안 A — 만료 검사를 시스템존 now() 로**: OrderService 와 맞지만 주입형 Clock(테스트 결정성)을 버리고 시스템 존에 의존(이식성↓).
- **방안 B — 생성도 주입 Clock(UTC) 으로 통일(채택)**: 프로젝트 의도(UTC 단일 출처)에 부합, 테스트에서 고정 Clock 으로 결정적 검증 가능, 호스트 존에 무관.

### 4) 해결 (Resolution)
`OrderService` 에 `Clock` 를 주입하고 `timeoutAt(LocalDateTime.now(clock).plus(PAYMENT_TIMEOUT))` 로 변경 → 생성·검사가 **동일한 UTC Clock** 을 공유. `OrderServiceTest` 는 고정 Clock(`Clock` 목 + `Instant.parse(...)`/`ZoneOffset.UTC`)으로 만료 시각을 결정적으로 검증하도록 고쳤다.

### 5) 결과 (Result)
수정 후 e2e 재현: 만료 임박 결제가 즉시 만료 대상으로 잡혀 **`결제 만료 정리 1건` → 주문 CANCELLED·결제 EXPIRED·재고 해제 → 취소 이벤트 아웃박스(SENT) → `주문취소 이벤트 수신`** 까지 한 사이클이 흘렀다. 시간 의존 로직은 반드시 **단일 Clock 출처**로 통일해야 함을 확인. (잔여: 이벤트 occurredAt·감사 컬럼의 시스템존 now() 는 비교에 쓰이지 않는 표시값이라 현 범위 밖.)

## [No.11] 풀스택 컨테이너화 — 호스트 포트 충돌 + Kafka 듀얼 리스너 + 컨테이너 내 알림 게이트웨이

- **일자**: 2026-06-30
- **관련 Phase**: Phase 5 (앱 컨테이너화 + compose 통합 e2e 중 발견)
- **태그**: `#Docker` `#compose` `#Kafka리스너` `#포트충돌` `#통합배포`

### 1) 발견 (Discovery)
`docker compose --profile app up --build` 로 풀스택을 띄우자 이미지 빌드·인프라·worker 는 모두 정상이었으나 **api 컨테이너만 기동 실패**:
```
Error response from daemon: ... Bind for 0.0.0.0:8080 failed: port is already allocated
```

### 2) 분석 (Analysis)
세 가지가 얽혀 있었다.
- **(가) 호스트 8080 충돌**: 별도 레포인 `notify-gateway` 컨테이너(ADR-003)가 이미 호스트 8080 을 점유 중이었다. compose 의 api 포트 매핑이 `8080:8080` 고정이라 충돌.
- **(나) Kafka 리스너**: 기존 브로커는 `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092` 단일이라, compose 네트워크 내부 컨테이너(worker)가 `localhost:9092` 로는 **자기 자신**을 가리켜 브로커에 닿지 못한다(호스트 bootRun 전용 주소였음).
- **(다) 컨테이너 내 알림**: worker 컨테이너에서 `NOTIFY_GATEWAY_URL=http://localhost:8080` 은 호스트의 notify-gateway 가 아니라 **worker 컨테이너 자신**을 가리켜 `ConnectException` 발생.

### 3) 고찰 (Contemplation)
- (가) api 의 **호스트 포트와 컨테이너 내부 포트를 분리**한다. 내부는 8080 고정, 호스트 노출은 `API_PORT`(기본 8080)로 오버라이드 가능하게 → 충돌 환경에서 코드/이미지 변경 없이 회피.
- (나) 브로커에 **듀얼 리스너**를 둔다: 호스트용 `PLAINTEXT(localhost:9092)` + 내부용 `INTERNAL(kafka:29092)`. 호스트 도구는 9092, 컨테이너는 29092 로 **같은 브로커**에 접속.
- (다) 알림 실패는 `NotifyGatewayClient` 가 **흡수(폴백 후 건너뜀)**해 작업 흐름에 무영향이라 슬라이스 차단 사유 아님. 컨테이너에서 호스트 게이트웨이를 쓰려면 `host.docker.internal` 또는 게이트웨이를 같은 네트워크 service 로 두는 것이 정석(ADR-003 후속 범위).

### 4) 해결 (Resolution)
- compose api: `ports: ["${API_PORT:-8080}:8080"]`, 컨테이너 `SERVER_PORT: 8080` 고정. `.env.example` 에 `API_PORT` 키·설명 추가.
- compose kafka: `KAFKA_LISTENERS` 에 `INTERNAL://:29092` 추가, `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092,INTERNAL://kafka:29092`, `KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL`, 보안맵에 `INTERNAL:PLAINTEXT`. worker 는 `KAFKA_BOOTSTRAP=kafka:29092` 주입.

### 5) 결과 (Result)
`API_PORT=8081` 로 api 재기동 → 5개 컨테이너 모두 healthy, api `/actuator/health` 200. worker 가 `kafka:29092`(INTERNAL)로 접속해 `settlement`·`notification` 두 컨슈머 그룹 파티션 할당 완료. 브로커에 실제 `order.completed` 이벤트를 발행하니 **브로커 → 컨테이너 worker → 컨테이너 MariaDB** 로 관통, `settlement` 행(gross 50000 / fee 1500 / net 48500 / PENDING_PAYOUT) 생성 확인. 컨테이너 환경에서 호스트 포트 충돌·브로커 주소 가시성이 로컬 단일노드와 다르다는 점을 못박았다(잔여: 컨테이너↔호스트 알림 게이트웨이 연결은 ADR-003 후속).

## [No.12] api 컨테이너가 항상 unhealthy — CMD-SHELL 은 bash 가 아니라 dash 로 실행된다

- **일자**: 2026-07-02
- **관련 Phase**: Phase 5 (#22 검증 중 증상 관찰, 2026-07-02 전체 리뷰에서 원인 확정)
- **태그**: `#Docker` `#healthcheck` `#dash` `#devtcp` `#거짓음성`

### 1) 발견 (Discovery)
#22 풀스택 검증에서 api 는 `/actuator/health` 200 으로 분명 정상인데 `docker compose ps` 는 **항상 `unhealthy`** 로 표시했다(거짓 음성). 앱 자체 문제가 아니라 헬스체크 결함으로 보고 후속 항목으로 미뤄뒀다가, 전체 리뷰(인프라 영역)에서 원인이 확정됐다.

### 2) 분석 (Analysis)
헬스체크는 `test: ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/8080 && ..."]` 였다. 함정은 두 겹:
- `CMD-SHELL` 은 **`/bin/sh -c`** 로 실행되는데, `eclipse-temurin:21-jre`(Ubuntu 기반)의 `/bin/sh` 는 **dash** 다.
- `/dev/tcp/...` 는 파일시스템에 실존하지 않는 **bash 전용 가상 경로**라, dash 는 이를 실제 파일로 열려다 ENOENT 로 항상 실패한다.

원 주석은 "JRE 이미지엔 curl 이 없어 … bash /dev/tcp 로 확인" 이라 적었지만 정작 bash 로 실행되지 않았다(자기모순). 참고로 이 이미지는 Ubuntu 기반이라 **bash 자체는 `/bin/bash` 에 존재**한다 — 문제는 bash 부재가 아니라 `CMD-SHELL` 이 bash 를 부르지 않는다는 점.

### 3) 고찰 (Contemplation)
- **방안 A — curl 설치 후 HTTP 체크**: 정석이지만 run 스테이지에 apt 레이어(+이미지 크기) 추가.
- **방안 B — `CMD` 배열로 bash 명시 호출 + TCP 연결만 확인**: 의존성 0 이지만 포트 개방 = 건강이 아니다(DB 다운이어도 Tomcat 포트는 열려 있음 — 거짓 양성).
- **방안 C — bash /dev/tcp 로 actuator 를 직접 HTTP GET(채택)**: bash 를 명시 호출하되 포트 확인에 그치지 않고 `GET /actuator/health` 요청을 써 넣고 응답의 `"status":"UP"` 을 grep 한다. 의존성 0 + 실질 건강 검사(방안 A 의 효과)를 동시에.

### 4) 해결 (Resolution)
```yaml
test: ["CMD", "bash", "-c", 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3']
```
`CMD` 배열이 bash 를 직접 실행하므로 `/dev/tcp` 가 동작하고, HTTP/1.0 요청이라 Host 헤더 없이 응답 후 연결이 닫혀 grep 이 EOF 까지 읽는다. DB 다운 시 actuator 가 503 `{"status":"DOWN"}` 을 주므로 실질 장애도 unhealthy 로 잡힌다.

### 5) 결과 (Result)
`docker compose config` 파싱 통과. 교훈: **`CMD-SHELL` 의 셸은 이미지의 `/bin/sh` 이며 그것이 bash 라는 보장은 어디에도 없다.** bash 확장(`/dev/tcp`, `[[ ]]`, 프로세스 치환)을 헬스체크에 쓰려면 반드시 `["CMD", "bash", "-c", ...]` 로 명시 호출할 것. (컨테이너 재기동 검증은 슬라이스 3 의 CI docker build 잡과 다음 풀스택 e2e 에서 수행.)

---

## [No.13] SSE 대량 이탈이 ERROR 로그 폭탄으로 — 클라이언트 끊김을 500 시스템 오류로 오분류

- **일자**: 2026-07-06
- **관련 Phase**: Phase 5 (통합 부하테스트)
- **태그**: `#SSE` `#예외처리` `#관측성` `#부하테스트`

### 1) 발견 (Discovery)
> M8 SSE 브로드캐스트 벤치(구독 수천 개를 열었다가 측정 종료 시 일괄 종료) 직후, api 컨테이너 로그에 ERROR 가 수천 건 쏟아졌다.

```
2026-07-06T01:04:44.685Z ERROR 1 --- [schale-queue-api] [at-handler-3478] c.s.q.api.common.GlobalExceptionHandler  : 처리되지 않은 예외 — 500 으로 응답
org.springframework.web.context.request.async.AsyncRequestNotUsableException: Servlet container error notification for disconnected client
	at org.springframework.web.context.request.async.StandardServletAsyncWebRequest.lambda$onError$0(StandardServletAsyncWebRequest.java:195) ~[spring-web-6.2.6.jar!/:6.2.6]
	at org.apache.catalina.core.AsyncListenerWrapper.fireOnError(AsyncListenerWrapper.java:49) ~[tomcat-embed-core-10.1.40.jar!/:na]
```

### 2) 분석 (Analysis)
`AsyncRequestNotUsableException` 은 **클라이언트가 비동기 요청(SSE)을 먼저 끊었다**는 서블릿 컨테이너의 통지다. 서버 결함이 아니라 정상 이탈 경로인데, 리뷰 M4 로 도입한 **500 catch-all**(`@ExceptionHandler(Exception.class)`)이 이를 시스템 오류로 오분류해 `log.error` + 스택트레이스로 남겼다. 브라우저 새로고침/이탈이 잦은 SSE 특성상, 대량 접속 상황에서는 이 오탐이 로그를 지배해 **진짜 ERROR 를 묻어버린다**(관측성 훼손). 응답 역시 이미 끊긴 연결이라 500 본문을 쓸 수도 없다.

### 3) 고찰 (Contemplation)
- **방안 A — catch-all 내부에서 instanceof 분기**: 코드 한 곳. 단, catch-all 의 "여기 오면 전부 시스템 오류"라는 단일 책임이 흐려진다.
- **방안 B — 전용 `@ExceptionHandler` 추가**: 매핑 의도가 시그니처로 드러나고 catch-all 은 순수하게 유지된다. 스프링이 더 구체적인 핸들러를 우선 선택하므로 동작도 자명하다. 단점: 핸들러 1개 증가.
- **방안 C — 로깅 필터/레벨 조정으로 숨기기**: 코드 무변경. 그러나 500 응답 시도(무의미한 본문 직렬화)는 그대로고, 근본 분류 오류를 로그 설정으로 가리는 것뿐이다.

### 4) 해결 (Resolution)
방안 B 채택 — `GlobalExceptionHandler` 에 `AsyncRequestNotUsableException` 전용 핸들러를 추가했다. `log.debug` 로 강등하고(정상 이탈), 본문 없는 응답을 반환한다(연결이 이미 죽어 어차피 전송 불가).

### 5) 결과 (Result)
동일 벤치 재실행 시 ERROR 오탐 0건. SSE 대량 이탈은 debug 레벨로만 관측된다. 교훈: **catch-all 을 두는 순간, "예외지만 오류가 아닌 것"(클라이언트 이탈, 취소)을 명시적으로 골라내는 짝 핸들러가 반드시 따라와야 한다.** 부하테스트는 기능 결함만이 아니라 이런 관측성 결함도 드러낸다.

---

## [No.14] CI 레드인데 머지됨 — 파이프가 삼킨 exit code 가 머지 게이트를 무력화

- **일자**: 2026-07-06
- **관련 Phase**: Phase 5 이후 (백로그 정리)
- **태그**: `#CI` `#프로세스` `#GitHubFlow` `#셸`

### 1) 발견 (Discovery)
> 백로그 PR #33 머지 직후 체크 로그를 보니 `build & test` 잡이 **fail** 인데 머지가 실행돼 있었다.

```
build & test (JDK 21)	fail	1m36s	https://github.com/.../job/85300828543
docker image build (api·worker)	pass	...
(그리고 이어서) d882547 fix(core,worker): 리뷰 백로그 M2·M7·M10 종결 ... (#33)   ← 레드인데 main 진입
```

실패 원인 자체는 `PaymentLifecycleConcurrencyTest` 가 구 시맨틱("확정 시 슬롯 유지")을 단언 — M7(확정 시 슬롯 반납)과 충돌.

### 2) 분석 (Analysis)
머지 명령이 `gh pr checks 33 --watch 2>&1 | tail -4 && gh pr merge ...` 형태였다. **파이프라인의 exit code 는 마지막 명령(tail)의 것**이므로, `gh pr checks` 가 실패(비 0)를 반환해도 `tail` 이 0 을 돌려줘 `&&` 게이트가 통과했다. 즉 §5.3.5 의 "CI 그린 확인 후 머지"가 **표시용 출력은 남기면서 판정 기능은 잃은** 상태였다. 이전 PR 들이 전부 그린이어서 이 결함이 잠복해 있었다. 부차적 원인: 게이트 IT(RUN_DB_IT)는 CI 전용 env 라 로컬 `./gradlew build` 로는 재현되지 않았다.

### 3) 고찰 (Contemplation)
- **방안 A — 파이프 제거**: `gh pr checks --watch` 를 단독 실행하고 exit code 를 변수로 받아 분기. 단순·확실. 출력 요약은 실패 시에만 다시 조회.
- **방안 B — `set -o pipefail`**: 파이프 전체의 실패를 전파. 셸 세션마다 잊지 않고 켜야 하고, Git Bash 비대화 실행에서 누락되기 쉽다.
- **방안 C — GitHub branch protection 서버 강제**: 근본적이지만 무료 플랜 + 비공개 레포 제약(§5.3.5 에 기록된 403)으로 불가 — 클라이언트 게이트가 유일한 방어선이라 A 가 필수.

### 4) 해결 (Resolution)
방안 A 채택. 머지 게이트를 다음 패턴으로 고정한다(§5.3.5 운용 규칙):

```bash
gh pr checks <PR> --watch > /dev/null 2>&1; CHECKS_EXIT=$?
if [ "$CHECKS_EXIT" -eq 0 ]; then gh pr merge <PR> --squash --delete-branch; else echo "CI RED — 머지 중단"; gh pr checks <PR>; fi
```

사고 자체는 fix-forward: 충돌 단언을 새 시맨틱으로 갱신한 #34 를 위 패턴으로 그린 머지해 main 을 복구했다. 아울러 core 도메인 시맨틱 변경 시, CI 전용 게이트 IT(RUN_DB_IT/RUN_REDIS_IT)를 **로컬에서도 선행 실행**하는 것을 체크리스트에 추가한다(app 컨테이너 정지 후 — 만료 워커 간섭, No. 기록 참조).

### 5) 결과 (Result)
#34 CI 그린 확인 후 머지, main `f199a1f` 로 복구 완료. 교훈: **게이트는 '보이는 출력'이 아니라 'exit code'다.** 출력 가공(pipe)을 판정 경로에 끼워 넣는 순간 게이트는 장식이 된다. 레드 머지 창(약 25분) 동안 배포·후속 분기는 없었어서 실피해는 없었다.

---

## [No.15] 게이트 IT 가 로컬에서 조용히 스킵됨 — Gradle 데몬이 낡은 환경변수를 재사용

- **일자**: 2026-07-06
- **관련 Phase**: Phase 5 이후 (백로그 정리)
- **태그**: `#Gradle` `#테스트` `#프로세스`

### 1) 발견 (Discovery)
> 잔여 백로그 검증에서 `RUN_DB_IT=true ... ./gradlew test --rerun` 이 13초 만에 그린으로 끝나 의심스러워 리포트 XML 을 열어보니:

```
TEST-...PaymentLifecycleConcurrencyTest.xml:  tests="1" skipped="1"
```

환경변수를 분명히 넘겼는데 `@EnabledIfEnvironmentVariable(named="RUN_DB_IT")` 게이트가 닫혀 있었다. 소급 확인 결과 **#34 의 "로컬 재현 그린"도 실제로는 스킵된 채의 공허한 그린**이었다(그땐 CI 가 실검증을 대신함).

### 2) 분석 (Analysis)
Gradle 은 기본적으로 **데몬**에서 빌드를 실행하고, 테스트 포크 JVM 의 환경은 **데몬 프로세스의 환경**을 물려받는다. 데몬은 처음 뜬 셸의 환경으로 살아남으므로, 이후 셸에서 `RUN_DB_IT=true ./gradlew ...` 로 넘긴 변수는 클라이언트에만 있고 **데몬(과 테스트 JVM)에는 없다**. 게이트 어노테이션은 조용히 skip 하므로 BUILD SUCCESSFUL 이 "통과"처럼 보인다.

### 3) 고찰 (Contemplation)
- **방안 A — `./gradlew --stop` 후 env 를 실은 셸에서 재기동**: 데몬이 새 env 로 뜬다. 간단하지만 "잊으면 재발"하는 절차 의존.
- **방안 B — build.gradle 에서 `test.environment` 로 클라이언트 env 명시 전달**: 구조적 해결이나, CI(잡 레벨 env)와 로컬의 이중 경로가 생기고 시크릿(TEST_DB_PASSWORD) 취급이 번거로움.
- **방안 C — 실행 여부를 결과로 검증**: 어느 방안이든 "그린"만 믿지 말고 리포트 XML 의 `skipped` 카운트를 함께 확인.

### 4) 해결 (Resolution)
A + C 채택. 게이트 IT 로컬 실행 절차를 다음으로 고정한다:

```bash
./gradlew --stop                                  # 낡은 데몬 제거
set -a; . ./.env; set +a
export RUN_DB_IT=true RUN_REDIS_IT=true TEST_DB_PASSWORD="$DB_PASSWORD"
./gradlew :module-core:test :module-api:test --rerun
grep -o 'skipped="[0-9]*"' backend/*/build/test-results/test/TEST-*IT*.xml   # 실행 확인(0 이어야)
```

### 5) 결과 (Result)
데몬 재기동 후 게이트 IT 3종(재고 동시성·결제 경합·SSE 스트림)이 실제 실행·통과함을 `skipped="0"` 으로 확인. 교훈: **게이트형 테스트의 그린은 두 번 의심하라 — "통과한 그린"과 "실행되지 않은 그린"은 로그 한 줄 차이다.** (No.14 의 "게이트는 exit code" 교훈의 짝: exit code 조차 스킵을 구분하지 못한다.)
