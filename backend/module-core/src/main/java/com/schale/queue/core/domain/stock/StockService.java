package com.schale.queue.core.domain.stock;

import com.schale.queue.core.domain.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 도메인 서비스.
 *
 * <p>선착순 동시성 제어의 심장. 재고 차감은 반드시 <b>비관적 쓰기 락</b>으로 보호된
 * 단일 트랜잭션 안에서 수행한다(ADR-001). 락 조회({@code findByGoodsIdWithPessimisticLock})
 * → 도메인 차감({@link Stock#decrease(int)}) → 커밋 시 dirty checking 으로 반영되는 흐름이며,
 * 임계 구역이 행 단위로 직렬화되어 초과 판매(oversell)가 원천 차단된다.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;

    /**
     * 지정 상품의 재고를 비관적 락 하에 차감한다.
     *
     * @param goodsId  대상 상품 ID
     * @param quantity 차감 수량(양수)
     * @throws IllegalArgumentException 해당 상품의 재고가 없는 경우
     * @throws IllegalStateException    잔여 재고가 부족한 경우(초과 판매 방지)
     */
    @Transactional
    public void decrease(Long goodsId, int quantity) {
        Stock stock = stockRepository.findByGoodsIdWithPessimisticLock(goodsId)
            .orElseThrow(() -> new IllegalArgumentException("재고가 존재하지 않습니다. goodsId=" + goodsId));

        stock.decrease(quantity);
    }
}
