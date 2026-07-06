package com.schale.queue.core.domain.goods;

import com.schale.queue.core.domain.NotFoundException;
import com.schale.queue.core.domain.goods.repository.GoodsRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 도메인 서비스.
 *
 * <p>책임은 <b>판매 시작 게이트</b>(UC-02)와 <b>상품 조회</b>(Phase 5 프론트엔드)다.
 * API 계층이 JPA 리포지토리를 직접 참조하지 않도록
 * (모듈 경계 — worker 와 달리 api 는 JPA 를 컴파일 의존에 두지 않는다) 서비스로 감싼다.
 */
@Service
@RequiredArgsConstructor
public class GoodsService {

    private final GoodsRepository goodsRepository;
    private final Clock clock;

    /**
     * 판매 시작 게이트(UC-02, 리뷰 M5). 상품이 없으면 404, {@code openAt} 이전이면 409 로 거부된다.
     * 존재하지 않는 goodsId 가 Redis 대기열에 흘러들어 영구 잔류하는 것도 함께 막는다.
     *
     * <p>Goods 는 저빈도 변경·캐시 가능 데이터(ADR-001)다. 대기열 진입 폭주가 이 조회를 병목으로
     * 만들면 캐시를 이 메서드 뒤에 도입한다(호출부 무변경).
     *
     * @throws NotFoundException    상품이 존재하지 않는 경우
     * @throws SaleNotOpenException 판매 시작 전인 경우
     */
    @Transactional(readOnly = true)
    public void checkSaleOpen(Long goodsId) {
        Goods goods = getGoods(goodsId);
        if (LocalDateTime.now(clock).isBefore(goods.getOpenAt())) {
            throw new SaleNotOpenException(
                "판매 시작 전입니다. goodsId=" + goodsId + ", openAt=" + goods.getOpenAt());
        }
    }

    /**
     * 전체 상품 목록을 조회한다(Phase 5 프론트 상품 목록 화면).
     * 데모 규모(수십 건)라 페이징 없이 전체를 반환한다 — 카탈로그가 커지면 Pageable 도입.
     */
    @Transactional(readOnly = true)
    public List<Goods> findAll() {
        return goodsRepository.findAll();
    }

    /**
     * 상품 단건을 조회한다.
     *
     * @throws NotFoundException 상품이 존재하지 않는 경우
     */
    @Transactional(readOnly = true)
    public Goods getGoods(Long goodsId) {
        return goodsRepository.findById(goodsId)
            .orElseThrow(() -> new NotFoundException("상품이 존재하지 않습니다. goodsId=" + goodsId));
    }
}
