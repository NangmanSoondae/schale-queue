package com.schale.queue.core.domain.stock;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 도메인 서비스 — 예약 기반 3-카운터 전이(ADR-004, P-S2).
 *
 * <p>선착순 동시성 제어의 심장. 모든 전이(예약/확정/해제)는 반드시 <b>비관적 쓰기 락</b>으로 보호된
 * 단일 트랜잭션 안에서 수행한다(P-S3). 락 조회({@code findByGoodsIdWithPessimisticLock})
 * → 도메인 전이({@link Stock#reserve(int)} 등) → 커밋 시 dirty checking 반영의 흐름이며,
 * 임계 구역이 행 단위로 직렬화되어 두 카운터가 원자적으로 함께 이동하고 오버셀이 원천 차단된다.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;

    /**
     * 주문 생성 시 재고를 예약한다(P-S2, {@code available-- reserved++}).
     *
     * @throws NotFoundException            해당 상품의 재고가 없는 경우
     * @throws InsufficientStockException   가용 재고가 부족한 경우(초과 판매 방지)
     */
    @Transactional
    public void reserve(Long goodsId, int quantity) {
        lockedStock(goodsId).reserve(quantity);
    }

    /**
     * 결제 확정 시 예약을 판매로 확정한다(P-S2, {@code reserved-- sold++}).
     * 호출 측(결제 성공 흐름, UC-06)은 Phase 4 에서 연결된다.
     */
    @Transactional
    public void confirm(Long goodsId, int quantity) {
        lockedStock(goodsId).confirm(quantity);
    }

    /**
     * 결제 만료/실패/취소 시 예약을 해제(복원)한다(P-S2/P-S4, {@code reserved-- available++}).
     * 호출 측(만료 워커/취소 흐름, UC-07)은 Phase 4 에서 연결된다.
     */
    @Transactional
    public void release(Long goodsId, int quantity) {
        lockedStock(goodsId).release(quantity);
    }

    private Stock lockedStock(Long goodsId) {
        return stockRepository.findByGoodsIdWithPessimisticLock(goodsId)
            .orElseThrow(() -> new NotFoundException("재고가 존재하지 않습니다. goodsId=" + goodsId));
    }
}
