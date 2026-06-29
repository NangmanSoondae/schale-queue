package com.schale.queue.core.domain.goods;

import com.schale.queue.core.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 엔티티.
 *
 * <p>읽기 위주의 저빈도 변경 데이터. 쓰기 경합이 집중되는 재고({@code Stock})와는
 * 1:1 로 분리되어, 재고 차감 락이 상품 조회에 간섭하지 않는다. (ADR-001 참조)
 */
@Getter
@Entity
@Table(name = "goods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goods extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // Flyway 마이그레이션(V1)의 TEXT 컬럼과 정합. @Lob(String→CLOB→tinytext) 는 ddl-auto=validate 에서
    // 실제 TEXT 컬럼과 타입이 어긋나 부팅을 막으므로, 컬럼 정의를 명시해 일치시킨다(troubleshooting No.05).
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 판매 가격 (KRW, 정수). */
    @Column(nullable = false)
    private Long price;

    /** 판매 오픈 일시 (선착순 시작 기준). */
    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    /**
     * 1인 구매 한도(P-O3). 회원의 해당 상품 활성(예약+확정) 수량이 이 값을 넘을 수 없다.
     * {@code null} 이면 무제한, 기본 1. "변경 적은 설정 메타정보"라 Stock 이 아닌 Goods 에 둔다(ADR-001 §2).
     */
    @Column(name = "max_purchase_per_member")
    private Integer maxPurchasePerMember;

    @Builder
    private Goods(String name, String description, Long price, LocalDateTime openAt, Integer maxPurchasePerMember) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.openAt = openAt;
        this.maxPurchasePerMember = maxPurchasePerMember;
    }
}
