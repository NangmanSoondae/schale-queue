-- ============================================================================
--  부하 테스트 시드 (Phase 3 ⑥)
--  ----------------------------------------------------------------------------
--  B1(기준부하/지연): goods 1002 + 대재고 5000 (큐 enqueue/position 지연 측정)
--  B2(동시성 극한/오버셀): goods 1001 + 최소재고 10 + 회원 N명 (오버셀 0건 검증)
--  회원은 MariaDB Sequence 엔진(seq_1_to_N)으로 대량 생성한다.
--  주문/항목/결제는 매 측정 전 run.sh 가 비우므로 여기서는 마스터 데이터만 시드한다.
-- ============================================================================

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM order_item;
DELETE FROM payment;
DELETE FROM orders;
DELETE FROM stock;
DELETE FROM goods;
DELETE FROM member;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO goods (id, name, description, price, open_at, created_at, updated_at) VALUES
    (1001, '부하-배송형(B2 오버셀)', NULL, 49000, NOW(), NOW(), NOW()),
    (1002, '부하-예매형(B1 지연)',  NULL, 49000, NOW(), NOW(), NOW());

INSERT INTO stock (goods_id, total_quantity, remain_quantity, created_at, updated_at) VALUES
    (1001, 10,   10,   NOW(), NOW()),
    (1002, 5000, 5000, NOW(), NOW());

-- 회원 1..1000 (B2 경합비 100:1 @ stock 10). 더 큰 경합이 필요하면 seq_1_to_N 의 N 을 키운다.
INSERT INTO member (id, email, password, name, role, created_at, updated_at)
SELECT seq, CONCAT('load', seq, '@test.local'), 'x', CONCAT('load', seq), 'USER', NOW(), NOW()
FROM seq_1_to_1000;
