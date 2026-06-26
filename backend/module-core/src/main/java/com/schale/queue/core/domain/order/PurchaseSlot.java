package com.schale.queue.core.domain.order;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 1인 구매 한도 슬롯(P-O3). 회원이 특정 상품에 대해 보유한 <b>활성(예약+확정) 주문 1건</b>을 표상한다.
 *
 * <p>{@code (member_id, goods_id)} 에 <b>유니크 제약</b>을 걸어, 같은 회원이 같은 상품을 동시에
 * 두 번 주문하는 경합을 <b>DB 차원에서 원자적으로</b> 차단한다(애플리케이션 SELECT-후-INSERT 의
 * TOCTOU 경합을 배제). 주문이 취소/만료되면 슬롯을 반납해 재시도가 가능해진다.
 *
 * <p>한도 "수량"({@code Goods.maxPurchasePerMember}) 검사와 달리, 이 슬롯은 "활성 주문 건수=1"을
 * 보장하는 장치다. 둘이 합쳐져 P-O3(1인 구매 한도)를 이룬다.
 */
@Getter
@Entity
@Table(
    name = "purchase_slot",
    uniqueConstraints = @UniqueConstraint(name = "uk_purchase_slot_member_goods", columnNames = {"member_id", "goods_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseSlot extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    /** 이 슬롯을 점유한 주문 FK (반납/추적용). */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Builder
    private PurchaseSlot(Long memberId, Long goodsId, Long orderId) {
        this.memberId = memberId;
        this.goodsId = goodsId;
        this.orderId = orderId;
    }
}
