-- ============================================================================
--  프론트엔드 데모 시드 (Phase 5)
--  ----------------------------------------------------------------------------
--  React 데모용 마스터 데이터: 판매중 상품 2종(재고 대/소) + 판매 예정 상품 1종.
--  회원 1..1000 — 프론트가 첫 방문 시 이 범위에서 무작위 ID 를 발급한다
--  (orders.member_id FK 때문에 회원 행이 실재해야 주문이 성공한다).
--  ⚠️ 기존 주문/재고 데이터를 전부 비우는 파괴적 시드다. 로컬 데모 전용.
--
--  적용:  docker compose exec -T mariadb \
--           sh -c 'mariadb -uschale -p"$MARIADB_PASSWORD" schale_queue' < load/seed/demo_seed.sql
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM order_item;
DELETE FROM payment;
DELETE FROM purchase_slot;
DELETE FROM orders;
DELETE FROM stock;
DELETE FROM goods;
DELETE FROM member;
SET FOREIGN_KEY_CHECKS = 1;

-- open_at 은 판매 시작 게이트(UC-02)가 UTC Clock 으로 비교하므로 UTC 기준으로 넣는다.
-- (NOW() 는 세션 존(Asia/Seoul) 기준이라 +9h 미래가 되어 SALE_NOT_OPEN 이 난다 — load seed 교훈)
INSERT INTO goods (id, name, description, price, open_at, max_purchase_per_member, created_at, updated_at) VALUES
    (1, '샬레 한정판 아크릴 스탠드', '선착순 한정 수량. 대기열을 통과해야 구매할 수 있습니다.', 29000,
        UTC_TIMESTAMP() - INTERVAL 1 HOUR, 2, NOW(), NOW()),
    (2, '총력전 기념 굿즈 세트', '재고 소량 — 오버셀 0건을 보장하는 동시성 제어 데모용.', 49000,
        UTC_TIMESTAMP() - INTERVAL 1 HOUR, 1, NOW(), NOW()),
    (3, '2026 여름 이벤트 티켓', '판매 예정 상품 — openAt 게이트(UC-02) 데모용.', 15000,
        UTC_TIMESTAMP() + INTERVAL 7 DAY, 1, NOW(), NOW());

-- 예약 모델(P-S2): available=total, reserved=0, sold=0
INSERT INTO stock (goods_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, created_at, updated_at) VALUES
    (1, 100, 100, 0, 0, NOW(), NOW()),
    (2, 5,   5,   0, 0, NOW(), NOW()),
    (3, 50,  50,  0, 0, NOW(), NOW());

-- 회원 1..1000 (MariaDB Sequence 엔진)
INSERT INTO member (id, email, password, name, role, created_at, updated_at)
SELECT seq, CONCAT('demo', seq, '@schale.local'), 'x', CONCAT('데모회원', seq), 'USER', NOW(), NOW()
FROM seq_1_to_1000;
