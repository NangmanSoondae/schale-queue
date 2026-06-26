package com.schale.queue.core.domain.order.repository;

import com.schale.queue.core.domain.order.PurchaseSlot;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 1인 구매 한도 슬롯 Repository(P-O3).
 */
public interface PurchaseSlotRepository extends JpaRepository<PurchaseSlot, Long> {

    /**
     * 슬롯 반납(주문 취소/결제 만료 시 호출 예정 — UC-07). 반납하면 같은 회원이 같은 상품을 재구매할 수 있다.
     * 호출 측(취소/만료 흐름)은 후속 슬라이스에서 연결된다.
     */
    void deleteByMemberIdAndGoodsId(Long memberId, Long goodsId);
}
