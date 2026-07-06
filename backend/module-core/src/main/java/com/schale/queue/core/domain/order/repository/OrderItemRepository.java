package com.schale.queue.core.domain.order.repository;

import com.schale.queue.core.domain.order.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 항목 Repository.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 특정 주문의 항목 목록 조회. */
    List<OrderItem> findByOrderId(Long orderId);

    /**
     * 회원의 상품별 <b>누적 유효 주문 수량</b>(취소 제외 = 예약 PENDING + 확정 COMPLETED)을 합산한다.
     * 1인 구매 한도(P-O3)의 수량 검사가 "이번 주문"이 아니라 <b>누적</b> 기준이 되도록 한다(리뷰 M7) —
     * 한도 2개 상품을 1개 산 회원은 1개를 더 살 수 있고, 2개째부터 거부된다.
     *
     * <p>⚠️ 일관 읽기(REPEATABLE READ 스냅샷)라 <b>빠른 선검사 전용</b>이다 — 트랜잭션 스냅샷 이후에
     * 커밋된 타 주문은 보이지 않는다. 최종 권위는 {@link #sumActiveQuantityForUpdate}(잠금 읽기)다(리뷰2 H-1).
     */
    @Query("""
        select coalesce(sum(oi.quantity), 0)
          from OrderItem oi, Order o
         where o.id = oi.orderId
           and o.memberId = :memberId
           and oi.goodsId = :goodsId
           and o.orderStatus <> com.schale.queue.core.domain.order.OrderStatus.CANCELLED
        """)
    long sumActiveQuantityByMemberIdAndGoodsId(@Param("memberId") Long memberId, @Param("goodsId") Long goodsId);

    /**
     * 위와 같은 합산을 <b>잠금 읽기(FOR UPDATE = current read)</b>로 수행한다(리뷰2 H-1 — 최종 권위).
     *
     * <p>일관 읽기 SUM 은 트랜잭션 스냅샷에 갇혀, "내 스냅샷 이후 커밋된 타 주문 + confirm 의 슬롯
     * 반납" 조합이 한도를 뚫는 창이 있었다(재고 락 대기 중 끼어드는 인터리빙). 잠금 읽기는 스냅샷이
     * 아니라 <b>최신 커밋본</b>을 읽고 스캔 행을 잠그므로, 슬롯 INSERT(동시 활성 직렬화) 이후 이
     * 재검사를 통과한 값이 곧 커밋 시점의 진실이다. 자기 트랜잭션의 새 주문 행도 포함되므로
     * 호출측 비교식은 {@code sum > limit}(초과 여부)이다.
     *
     * <p>네이티브인 이유: JPQL 집계에는 {@code @Lock} 힌트가 적용되지 않는다. 스캔 범위는
     * (member_id, goods_id) 로 좁아 잠금 경합은 같은 회원의 동시 주문에 국한된다.
     */
    @Query(value = """
        select coalesce(sum(oi.quantity), 0)
          from order_item oi
          join orders o on o.id = oi.order_id
         where o.member_id = :memberId
           and oi.goods_id = :goodsId
           and o.order_status <> 'CANCELLED'
           for update
        """, nativeQuery = true)
    Long sumActiveQuantityForUpdate(@Param("memberId") Long memberId, @Param("goodsId") Long goodsId);
}
