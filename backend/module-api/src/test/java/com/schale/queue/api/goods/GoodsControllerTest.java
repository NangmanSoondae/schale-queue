package com.schale.queue.api.goods;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schale.queue.core.config.TimeConfig;
import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.Goods;
import com.schale.queue.core.domain.goods.GoodsService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link GoodsController} 웹 슬라이스 테스트. 상품 목록/상세의 HTTP 계약을 검증한다.
 *
 * <p>{@code saleOpen} 판정에 실제 UTC Clock({@link TimeConfig})을 쓰므로, 테스트 상품의
 * openAt 은 충분히 먼 과거/미래로 잡아 시각 경계 플레이크를 배제한다.
 */
@WebMvcTest(GoodsController.class)
@Import(TimeConfig.class)
class GoodsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoodsService goodsService;

    private static Goods goods(Long id, String name, LocalDateTime openAt) {
        Goods goods = Goods.builder()
            .name(name)
            .description("테스트 상품")
            .price(49_000L)
            .openAt(openAt)
            .maxPurchasePerMember(1)
            .build();
        ReflectionTestUtils.setField(goods, "id", id);
        return goods;
    }

    @Test
    @DisplayName("상품 목록은 200 과 saleOpen(서버 시각 기준)을 포함해 반환한다")
    void list_returns_200_with_sale_open_flag() throws Exception {
        // given — 하나는 판매 중(과거 openAt), 하나는 판매 전(미래 openAt)
        given(goodsService.findAll()).willReturn(List.of(
            goods(1L, "판매중 상품", LocalDateTime.now().minusYears(1)),
            goods(2L, "판매전 상품", LocalDateTime.now().plusYears(1))
        ));

        // when & then
        mockMvc.perform(get("/api/v1/goods"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].name").value("판매중 상품"))
            .andExpect(jsonPath("$[0].saleOpen").value(true))
            .andExpect(jsonPath("$[1].saleOpen").value(false));
    }

    @Test
    @DisplayName("상품 단건 조회는 200 과 상세 필드를 반환한다")
    void detail_returns_200_with_fields() throws Exception {
        // given
        given(goodsService.getGoods(1L))
            .willReturn(goods(1L, "샬레 한정판 굿즈", LocalDateTime.now().minusYears(1)));

        // when & then
        mockMvc.perform(get("/api/v1/goods/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.name").value("샬레 한정판 굿즈"))
            .andExpect(jsonPath("$.price").value(49000))
            .andExpect(jsonPath("$.maxPurchasePerMember").value(1))
            .andExpect(jsonPath("$.saleOpen").value(true));
    }

    @Test
    @DisplayName("존재하지 않는 상품 조회는 404 NOT_FOUND 를 반환한다")
    void detail_returns_404_for_unknown_goods() throws Exception {
        // given
        given(goodsService.getGoods(999L))
            .willThrow(new NotFoundException("상품이 존재하지 않습니다. goodsId=999"));

        // when & then
        mockMvc.perform(get("/api/v1/goods/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
