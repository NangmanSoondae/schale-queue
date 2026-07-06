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
     * 1인 구매 한도(P-O3, 기본 1). 회원의 해당 상품 활성(예약+확정) 수량이 이 값을 넘을 수 없다.
     * "변경 적은 설정 메타정보"라 Stock 이 아닌 Goods 에 둔다(ADR-001 §2).
     *
     * <p>DB 컬럼 기본값(1)은 Hibernate 가 INSERT 에 항상 컬럼을 포함해 적용되지 않으므로
     * (값 생략 시 NULL=무제한 저장 — 문서상 "기본 1"의 정반대, 2026-07-02 리뷰 M6),
     * 빌더에서 미지정을 1 로 강제한다. 레거시 NULL 행은 무제한으로 읽히며(호출측 null 가드 유지),
     * 진짜 무제한 상품이 필요해지면 구매 슬롯 정책(1인 1활성주문) 재설계와 함께 도입한다.
     */
    @Column(name = "max_purchase_per_member")
    private Integer maxPurchasePerMember;

    /**
     * 결제창 수명(분, P-O2 — 기본 10, 허용 1~30). 인기 드랍의 예약 재고 회수 속도를 운영자가
     * 조절하는 손잡이다(11_domain_policy §11.3 보충). M6 교훈에 따라 기본값 강제는 빌더가 담당하고,
     * 레거시 NULL 행은 {@link #paymentTimeout()} 이 기본 10분으로 해석한다.
     */
    @Column(name = "payment_timeout_minutes")
    private Integer paymentTimeoutMinutes;

    /** P-O2 기본/허용 범위. */
    private static final int PAYMENT_TIMEOUT_DEFAULT_MINUTES = 10;
    private static final int PAYMENT_TIMEOUT_MIN_MINUTES = 1;
    private static final int PAYMENT_TIMEOUT_MAX_MINUTES = 30;

    @Builder
    private Goods(String name, String description, Long price, LocalDateTime openAt,
                  Integer maxPurchasePerMember, Integer paymentTimeoutMinutes) {
        if (paymentTimeoutMinutes != null
            && (paymentTimeoutMinutes < PAYMENT_TIMEOUT_MIN_MINUTES
                || paymentTimeoutMinutes > PAYMENT_TIMEOUT_MAX_MINUTES)) {
            throw new IllegalArgumentException(
                "결제 타임아웃은 " + PAYMENT_TIMEOUT_MIN_MINUTES + "~" + PAYMENT_TIMEOUT_MAX_MINUTES
                    + "분이어야 합니다(P-O2). 입력=" + paymentTimeoutMinutes);
        }
        this.name = name;
        this.description = description;
        this.price = price;
        this.openAt = openAt;
        this.maxPurchasePerMember = maxPurchasePerMember != null ? maxPurchasePerMember : 1;
        this.paymentTimeoutMinutes =
            paymentTimeoutMinutes != null ? paymentTimeoutMinutes : PAYMENT_TIMEOUT_DEFAULT_MINUTES;
    }

    /** 결제창 수명(P-O2). 레거시 NULL 행은 기본 10분으로 해석한다. */
    public java.time.Duration paymentTimeout() {
        int minutes = paymentTimeoutMinutes != null ? paymentTimeoutMinutes : PAYMENT_TIMEOUT_DEFAULT_MINUTES;
        return java.time.Duration.ofMinutes(minutes);
    }
}
