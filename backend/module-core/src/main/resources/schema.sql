-- ============================================================================
--  Schale Queue - Schema DDL (MariaDB 10.6)
--  ----------------------------------------------------------------------------
--  - Engine : InnoDB (row-level lock, FK 지원)
--  - Charset: utf8mb4 (이모지/다국어 안전)
--  - 설계 근거: docs/6_adr_001_entity_design.md (ADR-001) 참조
--    · Goods ↔ Stock 1:1 분리 → 읽기 부하와 재고 쓰기 경합 격리
--    · Order ↔ Payment 1:1 분리 → 외부 결제 변동성 격리
--    · FK 및 주요 조회 컬럼에 인덱스 부여 → 대량 조회 성능 확보
--  실행 순서: 부모 → 자식 (FK 의존성 순). 역순으로 DROP.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS payment;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS stock;
DROP TABLE IF EXISTS goods;
DROP TABLE IF EXISTS member;

SET FOREIGN_KEY_CHECKS = 1;

-- ----------------------------------------------------------------------------
--  member : 회원
-- ----------------------------------------------------------------------------
CREATE TABLE member (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    email       VARCHAR(255) NOT NULL                COMMENT '로그인 이메일(고유)',
    password    VARCHAR(255) NOT NULL                COMMENT '암호화된 비밀번호(해시)',
    name        VARCHAR(50)  NOT NULL                COMMENT '회원 이름',
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '권한: USER / ADMIN',
    created_at  DATETIME     NOT NULL                COMMENT '생성 일시',
    updated_at  DATETIME     NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '회원';

-- ----------------------------------------------------------------------------
--  goods : 상품 (읽기 위주 / 저빈도 변경 → 캐시 친화)
-- ----------------------------------------------------------------------------
CREATE TABLE goods (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    name        VARCHAR(255) NOT NULL                COMMENT '상품명',
    description TEXT         NULL                     COMMENT '상품 설명',
    price       BIGINT       NOT NULL                COMMENT '판매 가격(KRW, 정수)',
    open_at     DATETIME     NOT NULL                COMMENT '판매 오픈 일시(선착순 시작)',
    created_at  DATETIME     NOT NULL                COMMENT '생성 일시',
    updated_at  DATETIME     NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    KEY idx_goods_open_at (open_at)  -- 오픈 예정/진행 상품 조회용
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '상품';

-- ----------------------------------------------------------------------------
--  stock : 재고 (쓰기 경합 집중 → goods 와 1:1 분리)
--  remain_quantity 는 CHECK 로 음수(초과판매) 방지 — DB 차원의 최후 안전망
-- ----------------------------------------------------------------------------
CREATE TABLE stock (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'PK',
    goods_id        BIGINT NOT NULL                COMMENT '상품 FK (1:1)',
    total_quantity  INT    NOT NULL                COMMENT '총 재고 수량',
    remain_quantity INT    NOT NULL                COMMENT '잔여 재고 수량',
    created_at      DATETIME NOT NULL              COMMENT '생성 일시',
    updated_at      DATETIME NOT NULL              COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_goods_id (goods_id),  -- 1:1 보장
    CONSTRAINT fk_stock_goods FOREIGN KEY (goods_id) REFERENCES goods (id),
    CONSTRAINT chk_stock_remain_non_negative CHECK (remain_quantity >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '재고';

-- ----------------------------------------------------------------------------
--  orders : 주문 ('order' 는 예약어 → 테이블명 orders)
-- ----------------------------------------------------------------------------
CREATE TABLE orders (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'PK',
    member_id    BIGINT      NOT NULL                COMMENT '주문 회원 FK',
    order_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '주문 상태: PENDING / COMPLETED / CANCELLED',
    total_amount BIGINT      NOT NULL                COMMENT '주문 총액(KRW)',
    created_at   DATETIME    NOT NULL                COMMENT '생성 일시',
    updated_at   DATETIME    NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    KEY idx_orders_member_id (member_id),        -- 회원별 주문 내역 조회
    KEY idx_orders_status (order_status),        -- 상태별 배치/통계 조회
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '주문';

-- ----------------------------------------------------------------------------
--  order_item : 주문 항목 (주문 1 : N 항목)
-- ----------------------------------------------------------------------------
CREATE TABLE order_item (
    id          BIGINT NOT NULL AUTO_INCREMENT COMMENT 'PK',
    order_id    BIGINT NOT NULL                COMMENT '주문 FK',
    goods_id    BIGINT NOT NULL                COMMENT '상품 FK',
    quantity    INT    NOT NULL                COMMENT '주문 수량',
    order_price BIGINT NOT NULL                COMMENT '주문 시점 단가(스냅샷)',
    created_at  DATETIME NOT NULL              COMMENT '생성 일시',
    updated_at  DATETIME NOT NULL              COMMENT '수정 일시',
    PRIMARY KEY (id),
    KEY idx_order_item_order_id (order_id),  -- 주문 상세 조회
    KEY idx_order_item_goods_id (goods_id),  -- 상품별 판매 집계
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_item_goods FOREIGN KEY (goods_id) REFERENCES goods (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '주문 항목';

-- ----------------------------------------------------------------------------
--  payment : 결제 (주문과 1:1 분리 / 외부 PG 연동·타임아웃 관리)
-- ----------------------------------------------------------------------------
CREATE TABLE payment (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    order_id    BIGINT       NOT NULL                COMMENT '주문 FK (1:1)',
    payment_uid VARCHAR(255) NULL                    COMMENT 'PG사 결제 승인번호(승인 전 NULL)',
    amount      BIGINT       NOT NULL                COMMENT '결제 금액(KRW)',
    status      VARCHAR(20)  NOT NULL DEFAULT 'READY' COMMENT '결제 상태: READY / PAID / FAILED / CANCELLED / EXPIRED',
    timeout_at  DATETIME     NULL                    COMMENT '결제 만료 시각(워커 정리 기준)',
    created_at  DATETIME     NOT NULL                COMMENT '생성 일시',
    updated_at  DATETIME     NOT NULL                COMMENT '수정 일시',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_id (order_id),     -- 1:1 보장
    UNIQUE KEY uk_payment_uid (payment_uid),       -- PG 승인번호 중복 방지
    KEY idx_payment_status (status),               -- 상태별 조회
    KEY idx_payment_timeout_at (timeout_at),       -- 만료 결제 배치 정리
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '결제';
