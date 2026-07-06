package com.schale.queue.api.goods;

import com.schale.queue.api.goods.dto.GoodsResponse;
import com.schale.queue.core.domain.goods.GoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 조회 API(Phase 5). 프론트엔드 상품 목록/상세 화면이 사용하는 읽기 전용 엔드포인트.
 *
 * <p>회원 식별이 필요 없는 공개 조회라 {@code X-Member-Id} 헤더를 받지 않는다.
 */
@Tag(name = "Goods", description = "상품 조회 API (공개 읽기 전용)")
@RestController
@RequestMapping("/api/v1/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;
    private final Clock clock;

    @Operation(
        summary = "상품 목록 조회",
        description = "전체 상품 목록을 반환한다. saleOpen 은 서버 UTC 시각 기준 판매 시작 여부."
    )
    @GetMapping
    public List<GoodsResponse> list() {
        LocalDateTime now = LocalDateTime.now(clock);
        return goodsService.findAll().stream()
            .map(goods -> GoodsResponse.from(goods, now))
            .toList();
    }

    @Operation(
        summary = "상품 단건 조회",
        description = "상품 상세를 반환한다. 상품이 없으면 404."
    )
    @GetMapping("/{goodsId}")
    public GoodsResponse detail(@PathVariable Long goodsId) {
        return GoodsResponse.from(goodsService.getGoods(goodsId), LocalDateTime.now(clock));
    }
}
