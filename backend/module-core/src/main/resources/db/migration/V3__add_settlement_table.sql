-- ============================================================================
--  V3 — 정산(settlement) 원장 테이블 (ADR-002 후방 컨슈머 확장)
--  발행측 무변경 슬라이스. order.completed/cancelled 를 새 컨슈머 그룹으로 구독해 적재.
--  FK 없음(아웃박스와 동일하게 도메인-발행 파이프라인을 디커플).
-- ============================================================================
CREATE TABLE settlement (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_id      BIGINT      NOT NULL                COMMENT '정산 대상 주문 ID',
    member_id     BIGINT      NOT NULL                COMMENT '주문 회원 ID',
    gross_amount  BIGINT      NOT NULL                COMMENT '주문 총액(KRW, 정산 기준 금액)',
    fee_amount    BIGINT      NOT NULL                COMMENT '플랫폼 수수료(KRW) = gross × rate(버림)',
    net_amount    BIGINT      NOT NULL                COMMENT '판매자 지급액(KRW) = gross − fee',
    status        VARCHAR(20) NOT NULL                COMMENT '정산 상태: PENDING_PAYOUT / REVERSED',
    settled_at    DATETIME    NOT NULL                COMMENT '정산 적재 시각',
    created_at    DATETIME    NOT NULL                COMMENT '생성 일시',
    updated_at    DATETIME    NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_order_id (order_id)      -- 주문당 1정산(컨슈머 멱등 2차 방어)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '정산 원장(수수료 차감)';
