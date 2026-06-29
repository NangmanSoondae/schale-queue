-- ============================================================================
--  V2 — 트랜잭셔널 아웃박스 + 컨슈머 멱등 테이블 (ADR-007)
--  S8 무유실 발행 슬라이스. FK 없음(발행 파이프라인을 도메인에서 디커플).
-- ============================================================================

-- ----------------------------------------------------------------------------
--  event_outbox : 트랜잭셔널 아웃박스 (무유실 발행)
--  비즈니스 변경과 '같은 트랜잭션'으로 이벤트를 행으로 기록 → 워커 릴레이가 Kafka 로 발행.
-- ----------------------------------------------------------------------------
CREATE TABLE event_outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(36)  NOT NULL                COMMENT '이벤트 고유 ID(UUID, 컨슈머 멱등 키)',
    aggregate_type VARCHAR(50)  NOT NULL                COMMENT '애그리거트 종류(예: ORDER)',
    aggregate_id   VARCHAR(64)  NOT NULL                COMMENT '애그리거트 ID(예: 주문 ID)',
    topic          VARCHAR(100) NOT NULL                COMMENT '발행 대상 Kafka 토픽',
    msg_key        VARCHAR(64)  NULL                    COMMENT 'Kafka 메시지 키(파티션 내 순서 보장용)',
    payload        TEXT         NOT NULL                COMMENT '직렬화된 이벤트 본문(JSON)',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '발행 상태: PENDING / SENT',
    sent_at        DATETIME     NULL                    COMMENT '발행(broker ack) 완료 시각',
    created_at     DATETIME     NOT NULL                COMMENT '생성(적재) 일시',
    updated_at     DATETIME     NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_outbox_event_id (event_id),   -- 이벤트 중복 적재 방지
    KEY idx_event_outbox_status (status, id)          -- 릴레이 PENDING 폴링(오래된 순)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '이벤트 아웃박스(무유실 발행)';

-- ----------------------------------------------------------------------------
--  processed_event : 컨슈머 멱등 기록
--  at-least-once 전달로 중복 수신될 수 있어, (event_id, consumer_group) 유니크로 재처리를 차단.
-- ----------------------------------------------------------------------------
CREATE TABLE processed_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(36) NOT NULL                COMMENT '처리한 이벤트 ID(event_outbox.event_id)',
    consumer_group VARCHAR(50) NOT NULL                COMMENT '처리한 컨슈머 그룹',
    created_at     DATETIME    NOT NULL                COMMENT '처리(기록) 일시',
    updated_at     DATETIME    NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_event_id_group (event_id, consumer_group)  -- 그룹별 1회 처리 보장(멱등)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '컨슈머 멱등 처리 기록';
