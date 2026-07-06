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
 * 1인 구매 한도 슬롯(P-O3). 회원이 특정 상품에 대해 보유한 <b>활성(PENDING) 주문 1건</b>을 표상한다.
 *
 * <p>{@code (member_id, goods_id)} 에 <b>유니크 제약</b>을 걸어, 같은 회원이 같은 상품을 동시에
 * 두 번 주문하는 경합을 <b>DB 차원에서 원자적으로</b> 차단한다(애플리케이션 SELECT-후-INSERT 의
 * TOCTOU 경합을 배제). 주문이 <b>확정/취소/만료로 활성 상태를 벗어나면 슬롯을 반납</b>해
 * 재주문이 가능해진다(리뷰 M7 — 확정 시 미반납이던 과거엔 사실상 1인 1주문이었다).
 *
 * <p>누적 "수량" 한도({@code Goods.maxPurchasePerMember})는 {@code OrderService.createOrder} 의
 * 누적 유효 수량 검사가 담당한다. 슬롯(동시 활성 1건) + 누적 수량 검사가 합쳐져 P-O3 를 이룬다.
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
