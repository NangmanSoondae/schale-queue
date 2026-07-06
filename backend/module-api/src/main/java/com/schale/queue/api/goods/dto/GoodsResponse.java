package com.schale.queue.api.goods.dto;

import com.schale.queue.core.domain.goods.Goods;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 상품 조회 응답 DTO.
 *
 * <p>{@code saleOpen} 은 서버 UTC Clock 기준으로 계산해 내려준다 — 클라이언트가 자기 시계로
 * {@code openAt}(UTC LocalDateTime, 존 정보 없음)을 해석하면 시간대 오차로 게이트 판정이
 * 어긋나기 때문에(리뷰 '시간대 3원화' 계열), 판정의 진실은 서버가 소유한다.
 *
 * @param id                   상품 ID
 * @param name                 상품명
 * @param description          상품 설명
 * @param price                판매 가격(KRW)
 * @param openAt               판매 오픈 일시(UTC)
 * @param maxPurchasePerMember 1인 구매 한도(P-O3)
 * @param saleOpen             판매 시작 여부(서버 시각 기준)
 */
@Schema(description = "상품 조회 응답")
public record GoodsResponse(
    @Schema(description = "상품 ID", example = "1")
    Long id,

    @Schema(description = "상품명", example = "샬레 한정판 굿즈")
    String name,

    @Schema(description = "상품 설명", example = "선착순 한정 판매 상품")
    String description,

    @Schema(description = "판매 가격(KRW)", example = "49000")
    Long price,

    @Schema(description = "판매 오픈 일시(UTC)", example = "2026-07-01T00:00:00")
    LocalDateTime openAt,

    @Schema(description = "1인 구매 한도", example = "1")
    Integer maxPurchasePerMember,

    @Schema(description = "판매 시작 여부(서버 시각 기준)", example = "true")
    boolean saleOpen
) {

    public static GoodsResponse from(Goods goods, LocalDateTime now) {
        return new GoodsResponse(
            goods.getId(),
            goods.getName(),
            goods.getDescription(),
            goods.getPrice(),
            goods.getOpenAt(),
            goods.getMaxPurchasePerMember(),
            !now.isBefore(goods.getOpenAt())
        );
    }
}
